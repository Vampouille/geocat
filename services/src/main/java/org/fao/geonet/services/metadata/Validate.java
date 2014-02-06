//=============================================================================
//===	Copyright (C) 2001-2007 Food and Agriculture Organization of the
//===	United Nations (FAO-UN), United Nations World Food Programme (WFP)
//===	and United Nations Environment Programme (UNEP)
//===
//===	This program is free software; you can redistribute it and/or modify
//===	it under the terms of the GNU General Public License as published by
//===	the Free Software Foundation; either version 2 of the License, or (at
//===	your option) any later version.
//===
//===	This program is distributed in the hope that it will be useful, but
//===	WITHOUT ANY WARRANTY; without even the implied warranty of
//===	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
//===	General Public License for more details.
//===
//===	You should have received a copy of the GNU General Public License
//===	along with this program; if not, write to the Free Software
//===	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
//===
//===	Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
//===	Rome - Italy. email: geonetwork@osgeo.org
//==============================================================================

package org.fao.geonet.services.metadata;

import jeeves.constants.Jeeves;
import jeeves.server.ServiceConfig;
import jeeves.server.UserSession;
import jeeves.server.context.ServiceContext;
import org.fao.geonet.GeonetContext;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.domain.Schematron;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.kernel.schema.MetadataSchema;
import org.fao.geonet.repository.SchematronRepository;
import org.fao.geonet.services.NotInReadOnlyModeService;
import org.fao.geonet.services.Utils;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.input.SAXBuilder;

import java.io.File;
import java.util.List;

/**
 *  For editing : update leaves information. Access is restricted
 *  Validate current metadata record in session.
 *  
 *  FIXME : id MUST be the id of the current metadata record in session ?
 */
public class Validate extends NotInReadOnlyModeService {

	//--------------------------------------------------------------------------
	//---
	//--- Init
	//---
	//--------------------------------------------------------------------------

	public void init(String appPath, ServiceConfig params) throws Exception {}

	//--------------------------------------------------------------------------
	//---
	//--- Service
	//---
	//--------------------------------------------------------------------------

	public Element serviceSpecificExec(Element params, ServiceContext context) throws Exception
	{

		GeonetContext gc = (GeonetContext) context.getHandlerContext(Geonet.CONTEXT_NAME);
		DataManager   dataMan = gc.getBean(DataManager.class);

		UserSession session = context.getUserSession();

		String id = Utils.getIdentifierFromParameters(params, context);
		String schemaName = dataMan.getMetadataSchema(id);

		//--- validate metadata from session
		Element errorReport = new AjaxEditUtils(context).validateMetadataEmbedded(session, id, context.getLanguage());

		//--- update element and return status
		Element elResp = new Element(Jeeves.Elem.RESPONSE);
		elResp.addContent(new Element(Geonet.Elem.ID).setText(id));
		elResp.addContent(new Element("schema").setText(schemaName));
		elResp.addContent(errorReport);

		Element schematronTranslations = new Element("schematronTranslations");

		// --- add translations for schematrons
        List<Schematron> schematrons = context.getBean(SchematronRepository.class).findAllBySchemaName(schemaName);

		DataManager dm = context.getBean(DataManager.class);
		MetadataSchema metadataSchema = dm.getSchema(schemaName);
		String schemaDir = metadataSchema.getSchemaDir();
		SAXBuilder builder = new SAXBuilder();
		for (Schematron schematron : schematrons) {
			// it contains absolute path to the xsl file
			String rule = schematron.getFile();
			String ident = schematron.getRuleName();

			String file = schemaDir + File.separator + "loc" + File.separator
					+ context.getLanguage() + "/" + ident + ".xml";

			Document document = builder.build(file);
			Element element = document.getRootElement();

			Element s = new Element(ident);
			element.detach();
			s.addContent(element);
			schematronTranslations.addContent(s);

		}
		elResp.addContent(schematronTranslations);

		return elResp;
	}
}