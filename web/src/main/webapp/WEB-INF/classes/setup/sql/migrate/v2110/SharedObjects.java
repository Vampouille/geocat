package v2110;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import jeeves.xlink.XLink;
import org.fao.geonet.Constants;
import org.fao.geonet.DatabaseMigrationTask;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.domain.ISODate;
import org.fao.geonet.domain.Pair;
import org.fao.geonet.geocat.services.thesaurus.RepairRdfFiles;
import org.fao.geonet.kernel.KeywordBean;
import org.fao.geonet.kernel.Thesaurus;
import org.fao.geonet.languages.IsoLanguagesMapper;
import org.fao.geonet.util.GeocatXslUtil;
import org.fao.geonet.utils.Xml;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.Namespace;
import org.jdom.output.XMLOutputter;
import org.openrdf.model.GraphException;
import org.openrdf.sesame.config.AccessDeniedException;
import org.openrdf.sesame.config.ConfigurationException;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static jeeves.xlink.XLink.HREF;
import static jeeves.xlink.XLink.LOCAL_PROTOCOL;
import static jeeves.xlink.XLink.NAMESPACE_XLINK;
import static org.fao.geonet.geocat.kernel.reusable.SharedObjectStrategy.LUCENE_EXTRA_NON_VALIDATED;
import static org.fao.geonet.geocat.kernel.reusable.SharedObjectStrategy.LUCENE_EXTRA_VALIDATED;
import static org.fao.geonet.schema.iso19139.ISO19139Namespaces.GCO;
import static org.fao.geonet.schema.iso19139.ISO19139Namespaces.GMD;
import static org.fao.geonet.schema.iso19139.ISO19139Namespaces.SRV;
import static org.fao.geonet.schema.iso19139che.ISO19139cheNamespaces.CHE;

/**
 * @author Jesse on 10/31/2014.
 */
public class SharedObjects implements DatabaseMigrationTask {

    private final static Pattern ID_PATTERN = Pattern.compile(".*id=(\\d+).*");
    private final static Pattern ROLE_PATTERN = Pattern.compile(".*role=([^&]+).*");
    protected static final String PREPARED_STATEMENT_SQL = "INSERT INTO public.metadata(" +
                                                           "              id, uuid, schemaid, istemplate, isharvested, createdate, \n" +
                                                           "              changedate,  data, source, title, root, extra, owner, \n" +
                                                           "              rating, popularity, displayorder)\n" +
                                                           "    VALUES (" +
                                                           "              ?, ?, 'iso19139.che', 's', 'n', ?, ?, ?, ?, ?, ?, ?, " +
                                                           "              1, 0, 0, 0)";
    private static final Set<String> COUNTRIES = Sets.newHashSet("LI", "DE", "CH", "FR", "AT", "IT");
    private static final Map<String, String> COUNTRY_MAP = Maps.newHashMap();
    static {
        COUNTRY_MAP.put("schweiz", "CH");
        COUNTRY_MAP.put("suisse", "CH");
        COUNTRY_MAP.put("switzerland", "CH");
        COUNTRY_MAP.put("svizzera", "CH");

        COUNTRY_MAP.put("deutschland", "DE");
        COUNTRY_MAP.put("germany", "DE");
        COUNTRY_MAP.put("allemagne", "DE");
        COUNTRY_MAP.put("germania", "DE");

        COUNTRY_MAP.put("france", "FR");
        COUNTRY_MAP.put("frankreich", "FR");
        COUNTRY_MAP.put("francia", "FR");

        COUNTRY_MAP.put("austria", "AT");
        COUNTRY_MAP.put("osterreich", "AT");
        COUNTRY_MAP.put("österreich", "AT");
        COUNTRY_MAP.put("autriche", "AT");

        COUNTRY_MAP.put("italie", "IT");
        COUNTRY_MAP.put("italy", "IT");
        COUNTRY_MAP.put("italien", "IT");
        COUNTRY_MAP.put("italia", "IT");

        COUNTRY_MAP.put("liechtenstein", "LI");
    }

    @Override
    public void update(Connection connection) throws SQLException {
        try {
            AtomicInteger idIndex;
            String source;
            try (Statement statement = connection.createStatement()) {
                idIndex = getMaxMetadataId(statement);
                source = getSourceId(statement);
            }

            Map<String, String> formatIdMap = migrateFormats(idIndex, source, connection);
            Map<String, String> contactIdMap = migrateContacts(idIndex, source, connection);

            updateMetadata(connection, formatIdMap, contactIdMap);

            try (Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM useraddress WHERE userid IN (SELECT id FROM users WHERE profile = 'Shared');");
                statement.execute("DELETE FROM email WHERE user_id IN (SELECT id FROM users WHERE profile = 'Shared');");
                statement.execute("DELETE FROM users where profile='Shared'");
                statement.execute("DROP TABLE Formats");
            }
        } catch (java.sql.BatchUpdateException e) {
            System.out.println("-------------------------------  Error occurred updating shared objects  -------------------------------");
            e.printStackTrace();

            SQLException next = e.getNextException();
            while (next != null) {
                System.err.println("-------------------------------  Next error   ---------------------------");
                next.printStackTrace();
            }

            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void updateMetadata(Connection conn, Map<String, String> formatIdMap, Map<String, String> contactIdMap) throws
            SQLException, IOException, JDOMException, AccessDeniedException, GraphException, ConfigurationException {
        Path thesauriDir = Paths.get(System.getProperty("geonetwork.dir") + "/config/codelist");
        IsoLanguagesMapper mapper = new IsoLanguagesMapper(){
            {
                iso639_1_to_iso639_2IsoLanguagesMap.put("en", "eng");
                iso639_1_to_iso639_2IsoLanguagesMap.put("fr", "fre");
                iso639_1_to_iso639_2IsoLanguagesMap.put("it", "ita");
                iso639_1_to_iso639_2IsoLanguagesMap.put("de", "ger");
                iso639_1_to_iso639_2IsoLanguagesMap.put("ge", "ger");
                iso639_1_to_iso639_2IsoLanguagesMap.put("rm", "roh");

                iso639_2_to_iso639_1IsoLanguagesMap.put("eng", "en");
                iso639_2_to_iso639_1IsoLanguagesMap.put("fre", "fr");
                iso639_2_to_iso639_1IsoLanguagesMap.put("ger", "de");
                iso639_2_to_iso639_1IsoLanguagesMap.put("deu", "de");
                iso639_2_to_iso639_1IsoLanguagesMap.put("roh", "rm");
                iso639_2_to_iso639_1IsoLanguagesMap.put("ita", "it");
            }
        };

        String fname = "local";
        String type = "_none_";
        String dname = "non_validated";
        Path thesaurusFile = thesauriDir.resolve(fname + "/thesauri/" + type + "/" + dname + ".rdf");
        String siteURL = "http://site.uri.com";

        final Multimap<String, String> allKeywordIds = HashMultimap.create();
        final Map<Pair<String, String>, String> wordToIdLookup = Maps.newHashMap();

        Files.walkFileTree(thesauriDir, new SimpleFileVisitor<Path>(){
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().endsWith(".rdf")) {
                    try {
                        Path thesaurusFileNamePath = file.getName(file.getNameCount() - 1);
                        String thesaurusFileName = thesaurusFileNamePath.toString().substring(0, thesaurusFileNamePath.toString()
                                .lastIndexOf(".rdf"));
                        Path thesaurusCategory = file.getName(file.getNameCount() - 2);
                        Path localOrExternal = file.getName(file.getNameCount() - 4);
                        String thesaurusName = localOrExternal + "." + thesaurusCategory + "." + thesaurusFileName;

                        Element xml = Xml.loadFile(file);
                        if (localOrExternal.toString().equals("local")) {
                            RepairRdfFiles.repairRdfFile(file, xml);
                        }
                        ArrayList<Namespace> nSs = Lists.newArrayList(
                                Namespace.getNamespace("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#"),
                                Namespace.getNamespace("skos", "http://www.w3.org/2004/02/skos/core#"));
                        List<?> objects = Xml.selectNodes(xml, "*//node()[normalize-space(@rdf:about) != ''] | node()[normalize-space(@rdf:about) != '']", nSs);
                        for (Object object : objects) {
                            if (object instanceof Element) {
                                Element element = (Element) object;
                                String about = element.getAttributeValue("about", nSs.get(0));
                                allKeywordIds.put(thesaurusName, about);

                                List<?> notes = Xml.selectNodes(element, "skos:prefLabel", nSs);

                                for (Object note : notes) {
                                    Element noteEl = (Element) note;

                                    String noteText = noteEl.getTextTrim().toLowerCase();

                                    if (!noteText.isEmpty()) {
                                        String lang = noteEl.getAttributeValue("lang", Namespace.XML_NAMESPACE);
                                        if (lang == null) {
                                            lang = "de";
                                        }
                                        wordToIdLookup.put(Pair.read(lang, noteText), about);
                                        Pair<String, String> noLang = Pair.read(GeocatXslUtil.NO_LANG, noteText);
                                        if (!wordToIdLookup.containsKey(noLang)) {
                                            wordToIdLookup.put(noLang, about);
                                        }
                                        wordToIdLookup.put(Pair.read(lang, noteText), about);
                                    }
                                }
                            }
                        }
                    } catch (JDOMException e) {
                        throw new RuntimeException(e);
                    }
                }
                return super.visitFile(file, attrs);
            }
        });
        Thesaurus thesaurus = new Thesaurus(mapper, fname, type, dname, thesaurusFile, siteURL);
        thesaurus.initRepository();

        Map<String, Pair<String, Element>> missingKeywords = Maps.newHashMap();
        try (
                Statement select = conn.createStatement();
                PreparedStatement update = conn.prepareStatement("UPDATE metadata SET data=? WHERE id=?")
        ) {
                int numInBatch = 0;
                try (ResultSet results = select.executeQuery("select id, data from metadata where schemaid='iso19139.che' and isharvested='n'")) {
                    while (results.next()) {
                        int id = results.getInt("id");
                        String data = results.getString("data");

                        Element md = Xml.loadString(data, false);

                        final Iterator descendants = md.getDescendants();
                        while (descendants.hasNext()) {
                            Object node = descendants.next();
                            if (node instanceof Element) {
                                Element el = (Element) node;
                                final String atValue = el.getAttributeValue(HREF, NAMESPACE_XLINK);
                                if (el.getName().equals("identificationInfo")) {
                                    GeocatXslUtil.mergeKeywords(el, true, allKeywordIds, missingKeywords, wordToIdLookup);
                                } else if (atValue != null && atValue.contains("xml.format.get")) {
                                    String formatId = extractId(atValue);
                                    final String subtemplateUUID = formatIdMap.get(formatId);
                                    if (subtemplateUUID == null) {
                                        removeBrokenXLink(el);
                                    } else {
                                        el.setAttribute(HREF, LOCAL_PROTOCOL + "subtemplate?uuid=" + subtemplateUUID, NAMESPACE_XLINK);
                                    }
                                } else if (atValue != null && atValue.contains("xml.user.get")) {
                                    String userId = extractId(atValue);
                                    String role = extractRole(atValue);
                                    final String subtemplateUUID = contactIdMap.get(userId);

                                    if (subtemplateUUID == null) {
                                        removeBrokenXLink(el);
                                    } else {
                                        String href = LOCAL_PROTOCOL + "subtemplate?uuid=" + subtemplateUUID;
                                        if (role != null && !role.trim().isEmpty()) {
                                            href += "&process=*//gmd:CI_RoleCode/@codeListValue~" + role;
                                        }
                                        el.setAttribute(HREF, href, NAMESPACE_XLINK);
                                    }
                                }
                            }
                        }

                        String updatedData = Xml.getString(md);
                        update.setString(1, updatedData);
                        update.setInt(2, id);
                        update.addBatch();
                        numInBatch++;
                        if (numInBatch > 200) {
                            update.executeBatch();
                            numInBatch = 0;
                    }
                }
                update.executeBatch();
            }
        }
        if (!missingKeywords.isEmpty()) {
            for (Map.Entry<String, Pair<String, org.jdom.Element>> entry : missingKeywords.entrySet()) {
                KeywordBean keyword = new KeywordBean(mapper);
                keyword.setUriCode(URLDecoder.decode(entry.getValue().one(), Constants.ENCODING));
                List<?> nodes = Xml.selectNodes(entry.getValue().two(), "*//gmd:keyword//gmd:LocalisedCharacterString",
                        Arrays.asList(GMD, SRV, GCO));
                for (Object node : nodes) {
                    Element el = (Element) node;
                    String locale = el.getAttributeValue("locale");
                    if (locale != null && locale.length() > 0) {
                        locale = locale.substring(1).toLowerCase();
                    } else {
                        locale = "de";
                    }
                    String textTrim = el.getTextTrim();
                    if (!textTrim.isEmpty()) {
                        keyword.setValue(textTrim, locale);
                    }
                }

                thesaurus.addElement(keyword);
            }

        }
        thesaurus.getRepository().shutDown();
    }

    private void removeBrokenXLink(Element el) {
        el.removeAttribute(HREF, NAMESPACE_XLINK);
        el.removeAttribute(XLink.ROLE, NAMESPACE_XLINK);
        el.removeAttribute(XLink.SHOW, NAMESPACE_XLINK);
        el.removeAttribute(XLink.TITLE, NAMESPACE_XLINK);
    }

    private String extractId(String atValue) {
        final Matcher matcher = ID_PATTERN.matcher(atValue);
        if (!matcher.find()) {
            throw new Error(atValue + " does not match the pattern: " + ID_PATTERN);
        }
        return matcher.group(1);
    }

    private String extractRole(String atValue) {
        final Matcher matcher = ROLE_PATTERN.matcher(atValue);
        if (!matcher.find()) {
           return "pointOfContact";
        }
        return matcher.group(1);
    }

    private Map<String, String> migrateContacts(AtomicInteger idIndex, String source, Connection conn) throws SQLException,
            IOException {
        Map<String, String> idMap = Maps.newHashMap();
        try (
                PreparedStatement subtemplateStatement = conn.prepareStatement(PREPARED_STATEMENT_SQL);
                Statement selectStatement = conn.createStatement();
                ResultSet contacts = selectStatement.executeQuery("SELECT u1.*, u2.validated AS parentValidated FROM Users u1 " +
                                                                  "LEFT OUTER JOIN Users u2 ON u1.parentinfo = u2.id " +
                                                                  "WHERE u1.profile = 'Shared'");
        ) {
            while (contacts.next()) {
                String id = contacts.getString("id");
                Element contactEl = new Element("CHE_CI_ResponsibleParty", CHE);
                contactEl.setAttribute("isoType", "gmd:CI_ResponsibleParty", GCO);

                Element contactInfoEl = new Element("contactInfo", GMD);
                Element ciContactEl = new Element("CI_Contact", GMD);
                Element phoneEl = new Element("phone", GMD);
                Element ciTelephoneEl = new Element("CHE_CI_Telephone", CHE);
                ciTelephoneEl.setAttribute("isoType", "gmd:CI_Telephone", GCO);

                Element addressEl = new Element("address", GMD);
                Element cheAddressEl = new Element("CHE_CI_Address", CHE);
                cheAddressEl.setAttribute("isoType", "gmd:CI_Address", GCO);

                Element onlineResourceEl = new Element("onlineResource", GMD);
                Element ciOnlineResourceEl = new Element("CI_OnlineResource", GMD);


                addLocalizedEl(contacts, contactEl, "organisation", "organisationName", GMD, false);
                addLocalizedEl(contacts, contactEl, "positionname", "positionName", GMD, false);

                contactEl.addContent(
                        contactInfoEl.addContent(
                                ciContactEl.addContent(
                                        phoneEl.addContent(ciTelephoneEl))));

                addCharacterString(contacts, ciTelephoneEl, "phone", "voice", GMD, false);
                addCharacterString(contacts, ciTelephoneEl, "phone1", "voice", GMD, false);
                addCharacterString(contacts, ciTelephoneEl, "phone2", "voice", GMD, false);

                addCharacterString(contacts, ciTelephoneEl, "facsimile", "facsimile", GMD, false);
                addCharacterString(contacts, ciTelephoneEl, "facsimile1", "facsimile", GMD, false);
                addCharacterString(contacts, ciTelephoneEl, "facsimile2", "facsimile", GMD, false);

                addCharacterString(contacts, ciTelephoneEl, "directnumber", "directNumber", CHE, false);
                addCharacterString(contacts, ciTelephoneEl, "mobile", "mobile", CHE, false);

                ciContactEl.addContent(addressEl.addContent(cheAddressEl));

                addCharacterString(contacts, cheAddressEl, "city", "city", GMD, false);
                addCharacterString(contacts, cheAddressEl, "state", "administrativeArea", GMD, false);
                addCharacterString(contacts, cheAddressEl, "zip", "postalCode", GMD, false);
                String country = addCharacterString(contacts, cheAddressEl, "country", "country", GMD, false);
                if (!country.trim().isEmpty() && !COUNTRIES.contains(country) && COUNTRY_MAP.containsKey(country.toLowerCase())) {
                    cheAddressEl.getChild("country", GMD).getChild("CharacterString", GCO).setText(COUNTRY_MAP.get(country.toLowerCase()));
                }
                String email = addCharacterString(contacts, cheAddressEl, "email", "electronicMailAddress", GMD, false);
                addCharacterString(contacts, cheAddressEl, "email1", "electronicMailAddress", GMD, false);
                addCharacterString(contacts, cheAddressEl, "email2", "electronicMailAddress", GMD, false);
                addCharacterString(contacts, cheAddressEl, "streetname", "streetName", CHE, false);
                addCharacterString(contacts, cheAddressEl, "streetnumber", "streetNumber", CHE, false);
                addCharacterString(contacts, cheAddressEl, "address", "addressLine", CHE, false);
                addCharacterString(contacts, cheAddressEl, "postbox", "postBox", CHE, false);

                ciContactEl.addContent(onlineResourceEl.addContent(ciOnlineResourceEl));

                addLocalizedURL(contacts, ciOnlineResourceEl, "onlineresource", "linkage", GMD, false);
                if (!ciOnlineResourceEl.getChildren().isEmpty()) {
                    ciOnlineResourceEl.addContent(
                            new Element("protocol", GMD).addContent(
                                    new Element("CharacterString", GCO).setText("text/html")));
                }
                addLocalizedEl(contacts, ciOnlineResourceEl, "onlinename", "name", GMD, false);
                addLocalizedEl(contacts, ciOnlineResourceEl, "onlinedescription", "description", GMD, false);
                cleanContact(ciOnlineResourceEl);

                addCharacterString(contacts, ciContactEl, "hoursofservice", "hoursOfService", GMD, false);
                addLocalizedEl(contacts, ciContactEl, "contactinstructions", "contactInstructions", GMD, false);

                contactEl.addContent(
                        new Element("role", GMD).addContent(
                                new Element("CI_RoleCode", GMD).
                                        setAttribute("codeList", "http://www.isotc211.org/2005/resources/codeList.xml#CI_RoleCode").
                                        setAttribute("codeListValue", "pointOfContact")
                        )
                );
                String name = addCharacterString(contacts, contactEl, "name", "individualFirstName", CHE, false);
                String surname = addCharacterString(contacts, contactEl, "surname", "individualLastName", CHE, false);

                addLocalizedEl(contacts, contactEl, "orgacronym", "organisationAcronym", CHE, false);

                String parentinfo = contacts.getString("parentinfo");

                if (parentinfo != null && !parentinfo.trim().isEmpty()) {
                    String parentUUid = idMap.get(parentinfo);
                    if (parentUUid == null) {
                        parentUUid = UUID.randomUUID().toString();
                        idMap.put(parentinfo, parentUUid);
                    }
                    String parentHref =  LOCAL_PROTOCOL + "subtemplate?uuid=" + parentUUid;
                    final Element parentResponsibleParty = new Element("parentResponsibleParty", CHE).
                            setAttribute(HREF, parentHref, NAMESPACE_XLINK).
                            setAttribute(XLink.SHOW, "embed", NAMESPACE_XLINK);

                    if (contacts.getString("parentValidated").equalsIgnoreCase("y")) {
                        parentResponsibleParty.setAttribute(XLink.ROLE, "embed", NAMESPACE_XLINK);
                    }

                    contactEl.addContent(parentResponsibleParty);
                }

                String validated = contacts.getString("validated");

                String emailInBrackets = "";
                if (email == null || !email.trim().isEmpty()) {
                    emailInBrackets = "(" + email + ")";
                }
                String title = name + " " + surname + emailInBrackets;
                String uuid = idMap.get(id);
                if (uuid == null) {
                    uuid = UUID.randomUUID().toString();
                }

                cleanContact(contactEl);
                SharedObject obj = new SharedObject(id, contactEl, title, validated, "che:CHE_CI_ResponsibleParty", uuid);
                registerSubtemplate(idIndex, source, subtemplateStatement, obj);
                idMap.put(obj.id, uuid);
            }
            subtemplateStatement.executeBatch();
        }
        return idMap;
    }

    private void addLocalizedURL(ResultSet contacts, Element parentEl, String columnName, String elName, Namespace ns,
                                 boolean required) throws IOException, SQLException {

        final String value = contacts.getString(columnName);
        Element newEl = new Element(elName, ns).setAttribute("type","che:PT_FreeURL_PropertyType", Geonet.Namespaces.XSI);

        if (value == null || value.trim().isEmpty()) {
            if (required) {
                addMissingCharString(newEl);
            }
        } else {
            final Element translations = loadInternalMultiLingualElem(value);
            if (!translations.getChildren().isEmpty()) {
                Element ptFreeURL = new Element("PT_FreeURL", CHE);
                addPtFreeURL(ptFreeURL, translations.getChildText("EN"), "#EN");
                addPtFreeURL(ptFreeURL, translations.getChildText("DE"), "#DE");
                addPtFreeURL(ptFreeURL, translations.getChildText("FR"), "#FR");
                addPtFreeURL(ptFreeURL, translations.getChildText("IT"), "#IT");
                addPtFreeURL(ptFreeURL, translations.getChildText("RM"), "#RM");
                if (ptFreeURL.getContentSize() > 0) {
                    newEl.addContent(ptFreeURL);
                }
            }
        }

        if (newEl.getContentSize() > 0) {
            parentEl.addContent(newEl);
        }
    }

    private void addPtFreeURL(Element newEl, String translation, String locale) {
        if (translation != null && !translation.trim().isEmpty()) {
            newEl.addContent(
                    new Element("URLGroup", CHE).addContent(
                            new Element("LocalisedURL", CHE).setAttribute("locale", locale).setText(translation.trim())
                    )
            );
        }
    }

    private void cleanContact(Element contactEl) {
        if (contactEl.getName().equalsIgnoreCase("protocol") ||
            contactEl.getName().equalsIgnoreCase("linkage") ||
            contactEl.getName().equalsIgnoreCase("CI_RoleCode")) {
            return;
        }
        if (contactEl.getName().equalsIgnoreCase("CharacterString") ||
            contactEl.getName().equalsIgnoreCase("LocalisedCharacterString") ||
            contactEl.getName().equalsIgnoreCase("LocalisedURL")) {
            if (contactEl.getTextTrim().isEmpty()) {
                contactEl.detach();
            }
            return;
        } else {
            for (Object o : Lists.newArrayList(contactEl.getChildren())) {
                cleanContact((Element) o);
            }
        }
        if (contactEl.getContentSize() == 0) {
            contactEl.detach();
        }
    }


    private void addLocalizedEl(ResultSet contacts, Element contactEl,
                                String columnName, String elName,
                                Namespace ns, boolean required) throws
            SQLException, IOException {
        final String value = contacts.getString(columnName);
        Element newEl = new Element(elName, ns).setAttribute("type","gmd:PT_FreeText_PropertyType", Geonet.Namespaces.XSI);

        if (value == null || value.trim().isEmpty()) {
            if (required) {
                addMissingCharString(newEl);
            }
        } else {
            final Element translations = loadInternalMultiLingualElem(value);
            if (!translations.getChildren().isEmpty()) {
                Element ptFreeText = new Element("PT_FreeText", GMD);
                newEl.addContent(ptFreeText);
                addPtFreeText(ptFreeText, translations.getChildText("EN"), "#EN");
                addPtFreeText(ptFreeText, translations.getChildText("DE"), "#DE");
                if (translations.getChildText("DE") == null || translations.getChildText("DE").trim().isEmpty()) {
                    addPtFreeText(ptFreeText, translations.getChildText("GE"), "#DE");
                }
                addPtFreeText(ptFreeText, translations.getChildText("FR"), "#FR");
                addPtFreeText(ptFreeText, translations.getChildText("IT"), "#IT");
                addPtFreeText(ptFreeText, translations.getChildText("RM"), "#RM");
            }
        }

        if (newEl.getContentSize() > 0) {
            contactEl.addContent(newEl);
        }
    }

    private void addPtFreeText(Element newEl, String translation, String locale) {
        if (translation != null && !translation.trim().isEmpty()) {
            newEl.addContent(
                    new Element("textGroup", GMD).addContent(
                            new Element("LocalisedCharacterString", GMD).setAttribute("locale", locale).setText(translation.trim())
                    )
            );
        }
    }

    public static Element loadInternalMultiLingualElem(String basicValue) throws IOException {

        final String xml = "<description>" + basicValue.replaceAll("(<\\w+>)\\s*(\\<!\\[CDATA\\[)*\\s*(.*?)\\s*(\\]\\]\\>)*(</\\w+>)",
                "$1<![CDATA[$3]]>$5") + "</description>";

        Element desc;
        try {
            desc = Xml.loadString(xml, false);
        } catch (JDOMException jdomParse) {
            try {
                String encoded = URLEncoder.encode(basicValue, "UTF-8");
                desc = Xml.loadString(String.format("<description><EN>%1$s</EN><DE>%1$s</DE><FR>%1$s</FR><IT>%1$s</IT></description>",
                        encoded), false);
            } catch (JDOMException e) {
                Element en = new Element("EN").setText("Error setting parsing text: " + basicValue);
                desc = new Element("description").addContent(en);
            }
        }

        for (Object o : Lists.newArrayList(desc.getChildren())) {
            Element el = (Element) o;
            if (el.getTextTrim().isEmpty()) {
                el.detach();
            }
        }
        return desc;
    }

    private String getSourceId(Statement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("SELECT value FROM Settings WHERE name = 'system/site/siteId'")) {
            resultSet.next();
            return resultSet.getString("value");
        }
    }

    private AtomicInteger getMaxMetadataId(Statement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("SELECT max(id) FROM metadata")) {
            resultSet.next();
            return new AtomicInteger(resultSet.getInt(1));
        }
    }

    private Map<String, String> migrateFormats(AtomicInteger idIndex, String source, Connection conn) throws SQLException {
        Map<String, String> idMap = Maps.newHashMap();
        try (
                PreparedStatement subtemplateStatement = conn.prepareStatement(PREPARED_STATEMENT_SQL);
                Statement selectStatement = conn.createStatement();
                ResultSet formats = selectStatement.executeQuery("SELECT * FROM Formats");
        ) {
            while (formats.next()) {
                String id = String.valueOf(formats.getInt("id"));
                String name = formats.getString("name");
                String validated = formats.getString("validated");

                Element formatEl = new Element("MD_Format", GMD);
                addCharacterString(formats, formatEl, "name", "name", GMD, true);
                addCharacterString(formats, formatEl, "version", "version", GMD, true);
                SharedObject obj = new SharedObject(id, formatEl, name, validated, "gmd:MD_Format");

                registerSubtemplate(idIndex, source, subtemplateStatement, obj);
                idMap.put(obj.id, obj.uuid);
            }
            subtemplateStatement.executeBatch();
        }
        return idMap;

    }

    private void registerSubtemplate(AtomicInteger idIndex, String source, PreparedStatement statement,
                                       SharedObject sharedObject) throws SQLException {
        int mdId = idIndex.incrementAndGet();
        String date = new ISODate().getDateAndTime();
        statement.setInt(1, mdId);
        statement.setString(2, sharedObject.uuid);
        statement.setString(3, date);
        statement.setString(4, date);
        statement.setString(5, sharedObject.getXml());
        statement.setString(6, source);
        statement.setString(7, sharedObject.name);
        statement.setString(8, sharedObject.root);
        statement.setString(9, sharedObject.validated);
        statement.addBatch();
    }

    private String addCharacterString(ResultSet results, Element parent, String columnName, String elemName, Namespace ns, boolean
            required) throws
            SQLException {

        final Element elem = new Element(elemName, ns);

        final String text = results.getString(columnName);
        if (text == null || text.trim().isEmpty()) {
            if (required) {
                addMissingCharString(elem);
            }
        } else {
            elem.addContent(new Element("CharacterString", GCO).setText(text));
        }
        if (elem.getContentSize() > 0) {
            parent.addContent(elem);
        }
        return text;
    }

    private void addMissingCharString(Element elem) {
        elem.setAttribute("nilReason", "missing", GCO).addContent(new Element("CharacterString", GCO));
    }

    private static final class SharedObject {
        final String id;
        final Element xml;
        final String name;
        final String validated;
        final String root;
        final String uuid;

        private SharedObject(String id, Element xml, String name, String validated, String root) {
            this(id, xml, name, validated, root, UUID.randomUUID().toString());
        }
        private SharedObject(String id, Element xml, String name, String validated, String root, String uuid) {
            this.id = id;
            this.xml = xml;
            this.name = name;
            this.validated = validated.equalsIgnoreCase("y") ? LUCENE_EXTRA_VALIDATED : LUCENE_EXTRA_NON_VALIDATED;
            this.root = root;
            this.uuid = uuid;
        }

        public String getXml() {
            return Xml.getString(this.xml);
        }
    }

    public void updateRdf(Path path) throws
            SQLException, IOException, JDOMException, AccessDeniedException, GraphException, ConfigurationException {
        Path thesauriDir = Paths.get("/home/fgravin/gc_data/config/codelist");
        IsoLanguagesMapper mapper = new IsoLanguagesMapper(){
            {
                iso639_1_to_iso639_2IsoLanguagesMap.put("en", "eng");
                iso639_1_to_iso639_2IsoLanguagesMap.put("fr", "fre");
                iso639_1_to_iso639_2IsoLanguagesMap.put("it", "ita");
                iso639_1_to_iso639_2IsoLanguagesMap.put("de", "ger");
                iso639_1_to_iso639_2IsoLanguagesMap.put("ge", "ger");
                iso639_1_to_iso639_2IsoLanguagesMap.put("rm", "roh");

                iso639_2_to_iso639_1IsoLanguagesMap.put("eng", "en");
                iso639_2_to_iso639_1IsoLanguagesMap.put("fre", "fr");
                iso639_2_to_iso639_1IsoLanguagesMap.put("ger", "de");
                iso639_2_to_iso639_1IsoLanguagesMap.put("deu", "de");
                iso639_2_to_iso639_1IsoLanguagesMap.put("roh", "rm");
                iso639_2_to_iso639_1IsoLanguagesMap.put("ita", "it");
            }
        };

        String fname = "local";
        String type = "_none_";
        String dname = "non_validated";
        Path thesaurusFile = thesauriDir.resolve(fname + "/thesauri/" + type + "/" + dname + ".rdf");
        String siteURL = "http://site.uri.com";

        final Multimap<String, String> allKeywordIds = HashMultimap.create();
        final Map<Pair<String, String>, String> wordToIdLookup = Maps.newHashMap();

        Files.walkFileTree(thesauriDir, new SimpleFileVisitor<Path>(){
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().endsWith(".rdf")) {
                    try {
                        Path thesaurusFileNamePath = file.getName(file.getNameCount() - 1);
                        String thesaurusFileName = thesaurusFileNamePath.toString().substring(0, thesaurusFileNamePath.toString()
                                .lastIndexOf(".rdf"));
                        Path thesaurusCategory = file.getName(file.getNameCount() - 2);
                        Path localOrExternal = file.getName(file.getNameCount() - 4);
                        String thesaurusName = localOrExternal + "." + thesaurusCategory + "." + thesaurusFileName;

                        Element xml = Xml.loadFile(file);
                        if (localOrExternal.toString().equals("local")) {
                            RepairRdfFiles.repairRdfFile(file, xml);
                        }
                        ArrayList<Namespace> nSs = Lists.newArrayList(
                                Namespace.getNamespace("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#"),
                                Namespace.getNamespace("skos", "http://www.w3.org/2004/02/skos/core#"));
                        List<?> objects = Xml.selectNodes(xml, "*//node()[normalize-space(@rdf:about) != ''] | node()[normalize-space(@rdf:about) != '']", nSs);
                        for (Object object : objects) {
                            if (object instanceof Element) {
                                Element element = (Element) object;
                                String about = element.getAttributeValue("about", nSs.get(0));
                                allKeywordIds.put(thesaurusName, about);

                                List<?> notes = Xml.selectNodes(element, "skos:prefLabel", nSs);

                                for (Object note : notes) {
                                    Element noteEl = (Element) note;

                                    String noteText = noteEl.getTextTrim().toLowerCase();

                                    if (!noteText.isEmpty()) {
                                        String lang = noteEl.getAttributeValue("lang", Namespace.XML_NAMESPACE);
                                        if (lang == null) {
                                            lang = "de";
                                        }
                                        wordToIdLookup.put(Pair.read(lang, noteText), about);
                                        Pair<String, String> noLang = Pair.read(GeocatXslUtil.NO_LANG, noteText);
                                        if (!wordToIdLookup.containsKey(noLang)) {
                                            wordToIdLookup.put(noLang, about);
                                        }
                                        wordToIdLookup.put(Pair.read(lang, noteText), about);
                                    }
                                }
                            }
                        }
                    } catch (JDOMException e) {
                        throw new RuntimeException(e);
                    }
                }
                return super.visitFile(file, attrs);
            }
        });
        Thesaurus thesaurus = new Thesaurus(mapper, fname, type, dname, thesaurusFile, siteURL);
        thesaurus.initRepository();

        Map<String, Pair<String, Element>> missingKeywords = Maps.newHashMap();

        final Element md = Xml.loadFile(path);

        final Iterator descendants = md.getDescendants();
        while (descendants.hasNext()) {
            Object node = descendants.next();
            if (node instanceof Element) {
                Element el = (Element) node;
                final String atValue = el.getAttributeValue(HREF, NAMESPACE_XLINK);
                if (el.getName().equals("identificationInfo")) {
                    GeocatXslUtil.mergeKeywords(el, true, allKeywordIds, missingKeywords, wordToIdLookup);
                }
            }
        }
        String finalMD = new XMLOutputter().outputString(md);

        if (!missingKeywords.isEmpty()) {
            KeywordBean keyword = new KeywordBean(mapper);
            for (Map.Entry<String, Pair<String, org.jdom.Element>> entry : missingKeywords.entrySet()) {
                keyword.setUriCode(URLDecoder.decode(entry.getValue().one(), Constants.ENCODING));
                List<?> nodes = Xml.selectNodes(entry.getValue().two(), "*//gmd:keyword//gmd:LocalisedCharacterString",
                        Arrays.asList(GMD, SRV, GCO));
                for (Object node : nodes) {
                    Element el = (Element) node;
                    String locale = el.getAttributeValue("locale");
                    if (locale != null && locale.length() > 0) {
                        locale = locale.substring(1).toLowerCase();
                    } else {
                        locale = "de";
                    }
                    String textTrim = el.getTextTrim();
                    if (!textTrim.isEmpty()) {
                        keyword.setValue(textTrim, locale);
                    }
                }

                thesaurus.addElement(keyword);
            }

        }
        thesaurus.getRepository().shutDown();
    }

    public void updateMetadataTest(Connection conn) throws
            SQLException, IOException, JDOMException, AccessDeniedException, GraphException, ConfigurationException {
        System.setProperty("geonetwork.dir", "/home/fgravin/gc_data");
        Path thesauriDir = Paths.get(System.getProperty("geonetwork.dir") + "/config/codelist");

        IsoLanguagesMapper mapper = new IsoLanguagesMapper(){
            {
                iso639_1_to_iso639_2IsoLanguagesMap.put("en", "eng");
                iso639_1_to_iso639_2IsoLanguagesMap.put("fr", "fre");
                iso639_1_to_iso639_2IsoLanguagesMap.put("it", "ita");
                iso639_1_to_iso639_2IsoLanguagesMap.put("de", "ger");
                iso639_1_to_iso639_2IsoLanguagesMap.put("ge", "ger");
                iso639_1_to_iso639_2IsoLanguagesMap.put("rm", "roh");

                iso639_2_to_iso639_1IsoLanguagesMap.put("eng", "en");
                iso639_2_to_iso639_1IsoLanguagesMap.put("fre", "fr");
                iso639_2_to_iso639_1IsoLanguagesMap.put("ger", "de");
                iso639_2_to_iso639_1IsoLanguagesMap.put("deu", "de");
                iso639_2_to_iso639_1IsoLanguagesMap.put("roh", "rm");
                iso639_2_to_iso639_1IsoLanguagesMap.put("ita", "it");
            }
        };

        String fname = "local";
        String type = "_none_";
        String dname = "non_validated";
        Path thesaurusFile = thesauriDir.resolve(fname + "/thesauri/" + type + "/" + dname + ".rdf");
        String siteURL = "http://site.uri.com";

        final Multimap<String, String> allKeywordIds = HashMultimap.create();
        final Map<Pair<String, String>, String> wordToIdLookup = Maps.newHashMap();

        Files.walkFileTree(thesauriDir, new SimpleFileVisitor<Path>(){
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().endsWith(".rdf")) {
                    try {
                        Path thesaurusFileNamePath = file.getName(file.getNameCount() - 1);
                        String thesaurusFileName = thesaurusFileNamePath.toString().substring(0, thesaurusFileNamePath.toString()
                                .lastIndexOf(".rdf"));
                        Path thesaurusCategory = file.getName(file.getNameCount() - 2);
                        Path localOrExternal = file.getName(file.getNameCount() - 4);
                        String thesaurusName = localOrExternal + "." + thesaurusCategory + "." + thesaurusFileName;

                        Element xml = Xml.loadFile(file);
                        if (localOrExternal.toString().equals("local")) {
                            RepairRdfFiles.repairRdfFile(file, xml);
                        }
                        ArrayList<Namespace> nSs = Lists.newArrayList(
                                Namespace.getNamespace("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#"),
                                Namespace.getNamespace("skos", "http://www.w3.org/2004/02/skos/core#"));
                        List<?> objects = Xml.selectNodes(xml, "*//node()[normalize-space(@rdf:about) != ''] | node()[normalize-space(@rdf:about) != '']", nSs);
                        for (Object object : objects) {
                            if (object instanceof Element) {
                                Element element = (Element) object;
                                String about = element.getAttributeValue("about", nSs.get(0));
                                allKeywordIds.put(thesaurusName, about);

                                List<?> notes = Xml.selectNodes(element, "skos:prefLabel", nSs);

                                for (Object note : notes) {
                                    Element noteEl = (Element) note;

                                    String noteText = noteEl.getTextTrim().toLowerCase();

                                    if (!noteText.isEmpty()) {
                                        String lang = noteEl.getAttributeValue("lang", Namespace.XML_NAMESPACE);
                                        if (lang == null) {
                                            lang = "de";
                                        }
                                        wordToIdLookup.put(Pair.read(lang, noteText), about);
                                        Pair<String, String> noLang = Pair.read(GeocatXslUtil.NO_LANG, noteText);
                                        if (!wordToIdLookup.containsKey(noLang)) {
                                            wordToIdLookup.put(noLang, about);
                                        }
                                        wordToIdLookup.put(Pair.read(lang, noteText), about);
                                    }
                                }
                            }
                        }
                    } catch (JDOMException e) {
                        throw new RuntimeException(e);
                    }
                }
                return super.visitFile(file, attrs);
            }
        });
        Thesaurus thesaurus = new Thesaurus(mapper, fname, type, dname, thesaurusFile, siteURL);
        thesaurus.initRepository();

        Map<String, Pair<String, Element>> missingKeywords = Maps.newHashMap();
        try (
                Statement select = conn.createStatement();
                PreparedStatement update = conn.prepareStatement("UPDATE metadata SET data=? WHERE id=?")
        ) {
            int numInBatch = 0;
            try (ResultSet results = select.executeQuery("select id, uuid, data from metadata where schemaid='iso19139.che' and isharvested='n'")) {
                while (results.next()) {
                    int id = results.getInt("id");
                    String uuid = results.getString("uuid");
                    String data = results.getString("data");

                    Element md = Xml.loadString(data, false);

                    final Iterator descendants = md.getDescendants();
                    while (descendants.hasNext()) {
                        Object node = descendants.next();
                        if (node instanceof Element) {
                            Element el = (Element) node;
                            final String atValue = el.getAttributeValue(HREF, NAMESPACE_XLINK);
                            if (el.getName().equals("identificationInfo")) {
                                GeocatXslUtil.mergeKeywords(el, true, allKeywordIds, missingKeywords, wordToIdLookup);
                            }
                        }
                    }

                    String updatedData = Xml.getString(md);
                    numInBatch++;


/*
                    update.setString(1, updatedData);
                    update.setInt(2, id);

                    update.addBatch();
                    numInBatch++;
                    if (numInBatch > 200) {
                        update.executeBatch();
                        numInBatch = 0;
                    }
*/

                }
                //update.executeBatch();
            }
        }
        if (!missingKeywords.isEmpty()) {
            for (Map.Entry<String, Pair<String, org.jdom.Element>> entry : missingKeywords.entrySet()) {
                KeywordBean keyword = new KeywordBean(mapper);
                keyword.setUriCode(URLDecoder.decode(entry.getValue().one(), Constants.ENCODING));
                List<?> nodes = Xml.selectNodes(entry.getValue().two(), "*//gmd:keyword//gmd:LocalisedCharacterString",
                        Arrays.asList(GMD, SRV, GCO));
                for (Object node : nodes) {
                    Element el = (Element) node;
                    String locale = el.getAttributeValue("locale");
                    if (locale != null && locale.length() > 0) {
                        locale = locale.substring(1).toLowerCase();
                    } else {
                        locale = "de";
                    }
                    String textTrim = el.getTextTrim();
                    if (!textTrim.isEmpty()) {
                        keyword.setValue(textTrim, locale);
                    }
                }

                thesaurus.addElement(keyword);
            }

        }
        thesaurus.getRepository().shutDown();
    }


}
