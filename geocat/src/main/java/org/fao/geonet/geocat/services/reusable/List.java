//==============================================================================
//===	Copyright (C) 2001-2008 Food and Agriculture Organization of the
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

package org.fao.geonet.geocat.services.reusable;

import jeeves.interfaces.Service;
import jeeves.server.ServiceConfig;
import jeeves.server.UserSession;
import jeeves.server.context.ServiceContext;
import org.fao.geonet.Util;
import org.fao.geonet.geocat.kernel.extent.ExtentManager;
import org.fao.geonet.geocat.kernel.reusable.ContactsStrategy;
import org.fao.geonet.geocat.kernel.reusable.DeletedObjects;
import org.fao.geonet.geocat.kernel.reusable.ExtentsStrategy;
import org.fao.geonet.geocat.kernel.reusable.FormatsStrategy;
import org.fao.geonet.geocat.kernel.reusable.KeywordsStrategy;
import org.fao.geonet.geocat.kernel.reusable.ReplacementStrategy;
import org.fao.geonet.geocat.kernel.reusable.ReusableTypes;
import org.fao.geonet.geocat.kernel.reusable.Utils;
import org.fao.geonet.kernel.ThesaurusManager;
import org.fao.geonet.kernel.setting.SettingManager;
import org.fao.geonet.languages.IsoLanguagesMapper;
import org.fao.geonet.repository.geocat.RejectedSharedObjectRepository;
import org.jdom.Element;

/**
 * Makes a list of all the shared elements of the given type (parameter validated selects if validated are listed)
 *
 * @author jeichar
 */
public class List implements Service
{

    public Element exec(Element params, ServiceContext context) throws Exception
    {
        String type = Util.getParam(params, "type", "contacts");
        boolean validated = Boolean.parseBoolean(Util.getParam(params, "validated", "false"));

        UserSession session = context.getUserSession();
        String appPath = context.getAppPath();
        String baseUrl = Utils.mkBaseURL(context.getBaseUrl(), context.getBean(SettingManager.class));
        String language = context.getLanguage();

        if (type.equals("deleted")) {
            return DeletedObjects.list(context.getBean(RejectedSharedObjectRepository.class));
        }

        ReplacementStrategy strategy;
        switch( ReusableTypes.valueOf(type) ) {
            case extents:
                strategy = new ExtentsStrategy(baseUrl, appPath, context.getBean(ExtentManager.class), language);
                break;
            case keywords:
                strategy = new KeywordsStrategy(context.getBean(IsoLanguagesMapper.class), context.getBean(ThesaurusManager.class),
                        appPath, baseUrl, language);
                break;
            case formats:
                strategy = new FormatsStrategy(context.getApplicationContext(), appPath);
                break;
            case contacts:
                strategy = new ContactsStrategy(context.getApplicationContext());
                break;
            default:
                throw new IllegalArgumentException(type + " is not a reusable object type");
        }

        return strategy.list(session, validated, language);
    }

    public void init(String appPath, ServiceConfig params) throws Exception
    {
    }

}