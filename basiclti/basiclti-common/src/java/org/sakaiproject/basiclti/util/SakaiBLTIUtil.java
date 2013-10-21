/**
 * $URL$
 * $Id$
 *
 * Copyright (c) 2006-2009 The Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.opensource.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sakaiproject.basiclti.util;

import java.util.Properties;
import java.util.Map;
import java.util.List;
import java.util.Iterator;
import java.net.URL;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.imsglobal.basiclti.BasicLTIUtil;
import org.imsglobal.basiclti.BasicLTIConstants;

import org.imsglobal.lti2.LTI2Constants;

import org.sakaiproject.lti.api.LTIService;

import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.cover.SessionManager;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.cover.UserDirectoryService;
import org.sakaiproject.site.api.ToolConfiguration;
import org.sakaiproject.tool.api.Placement;
import org.sakaiproject.authz.cover.SecurityService;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.cover.SiteService;
import org.sakaiproject.api.privacy.PrivacyManager;
import org.sakaiproject.authz.cover.AuthzGroupService;
import org.sakaiproject.authz.api.AuthzGroup;
import org.sakaiproject.authz.api.Role;
import org.sakaiproject.authz.api.Member;
import org.sakaiproject.authz.api.GroupNotDefinedException;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.util.ResourceLoader;
import org.sakaiproject.util.Web;
import org.sakaiproject.portal.util.CSSUtils;
import org.sakaiproject.linktool.LinkToolUtil;
import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.authz.cover.SecurityService;

import org.sakaiproject.service.gradebook.shared.AssignmentHasIllegalPointsException;
import org.sakaiproject.service.gradebook.shared.CategoryDefinition;
import org.sakaiproject.service.gradebook.shared.GradebookService;
import org.sakaiproject.service.gradebook.shared.GradebookExternalAssessmentService;
import org.sakaiproject.service.gradebook.shared.ConflictingAssignmentNameException;
import org.sakaiproject.service.gradebook.shared.ConflictingExternalIdException;
import org.sakaiproject.service.gradebook.shared.GradebookNotFoundException;
import org.sakaiproject.service.gradebook.shared.Assignment;

import net.oauth.OAuthAccessor;
import net.oauth.OAuthConsumer;
import net.oauth.OAuthMessage;
import net.oauth.OAuthValidator;
import net.oauth.SimpleOAuthValidator;
import net.oauth.server.OAuthServlet;
import net.oauth.signature.OAuthSignatureMethod;

/**
 * Some Sakai Utility code for IMS Basic LTI
 * This is mostly code to support the Sakai conventions for 
 * making and launching BLTI resources within Sakai.
 */
@SuppressWarnings("deprecation")
public class SakaiBLTIUtil {

    private static Log M_log = LogFactory.getLog(SakaiBLTIUtil.class);

	public static final boolean verbosePrint = false;

	public static final String BASICLTI_OUTCOMES_ENABLED = "basiclti.outcomes.enabled";
	public static final String BASICLTI_OUTCOMES_ENABLED_DEFAULT = "true";
	public static final String BASICLTI_SETTINGS_ENABLED = "basiclti.settings.enabled";
	public static final String BASICLTI_SETTINGS_ENABLED_DEFAULT = "true";
	public static final String BASICLTI_ROSTER_ENABLED = "basiclti.roster.enabled";
	public static final String BASICLTI_ROSTER_ENABLED_DEFAULT = "true";
	public static final String BASICLTI_LORI_ENABLED = "basiclti.lori.enabled";
	public static final String BASICLTI_LORI_ENABLED_DEFAULT = "true";
	public static final String BASICLTI_CONTENTLINK_ENABLED = "basiclti.contentlink.enabled";
	public static final String BASICLTI_CONTENTLINK_ENABLED_DEFAULT = null; // i.e. false
	public static final String BASICLTI_CONSUMER_USERIMAGE_ENABLED = "basiclti.consumer.userimage.enabled";
	public static final String BASICLTI_ENCRYPTION_KEY = "basiclti.encryption.key";

	public static void dPrint(String str)
	{
		if ( verbosePrint ) System.out.println(str);
	}

	// Retrieve the property from the configuration unless it
	// is overridden by the server configurtation (i.e. sakai.properties)
	public static String getCorrectProperty(Properties config,
			String propName, Placement placement)
	{
		// Check for global overrides in properties
		String allowSettings = ServerConfigurationService.getString(BASICLTI_SETTINGS_ENABLED, BASICLTI_SETTINGS_ENABLED_DEFAULT);
		if ( LTIService.LTI_ALLOWSETTINGS.equals(propName) && ! "true".equals(allowSettings) ) return "false";

		String allowRoster = ServerConfigurationService.getString(BASICLTI_ROSTER_ENABLED, BASICLTI_ROSTER_ENABLED_DEFAULT);
		if ( LTIService.LTI_ALLOWROSTER.equals(propName) && ! "true".equals(allowRoster) ) return "false";

		String allowLori = ServerConfigurationService.getString(BASICLTI_LORI_ENABLED, BASICLTI_LORI_ENABLED_DEFAULT);
		if ( LTIService.LTI_ALLOWLORI.equals(propName) && ! "true".equals(allowLori) ) return "false";

		String allowContentLink = ServerConfigurationService.getString(BASICLTI_CONTENTLINK_ENABLED, BASICLTI_CONTENTLINK_ENABLED_DEFAULT);
		if ( "contentlink".equals(propName) && ! "true".equals(allowContentLink) ) return null;

		// Check for explicit setting in properties
		String propertyName = placement.getToolId() + "." + propName;
		String propValue = ServerConfigurationService.getString(propertyName,null);
		if ( propValue != null && propValue.trim().length() > 0 ) {
			// System.out.println("Sakai.home "+propName+"="+propValue);
			return propValue;
		}

		// Take it from the placement
		return config.getProperty("imsti."+propName, null);
	}

	// Look at a Placement and come up with the launch urls, and
	// other launch parameters to drive the launch.
	public static boolean loadFromPlacement(Properties info, Properties launch, Placement placement)
	{
		Properties config = placement.getConfig();
		dPrint("Sakai properties=" + config);
		String launch_url = toNull(getCorrectProperty(config,LTIService.LTI_LAUNCH, placement));
		setProperty(info, "launch_url", launch_url);
		if ( launch_url == null ) {
			String xml = toNull(getCorrectProperty(config,"xml", placement));
			if ( xml == null ) return false;
			BasicLTIUtil.parseDescriptor(info, launch, xml);
		}

		String secret = getCorrectProperty(config,LTIService.LTI_SECRET, placement);

		// BLTI-195 - Compatibility mode for old-style encrypted secrets
		if ( secret == null || secret.trim().length() < 1 ) {
			String eSecret = getCorrectProperty(config,"encryptedsecret", placement);
			if ( eSecret != null && eSecret.trim().length() > 0 ) {
				secret = eSecret.trim() + ":" + SimpleEncryption.CIPHER;
			}
		}

		setProperty(info, LTIService.LTI_SECRET, secret );

		// This is not "consumerkey" on purpose - we are mimicking the old placement model
		setProperty(info, "key", getCorrectProperty(config,"key", placement) );
		setProperty(info, LTIService.LTI_DEBUG, getCorrectProperty(config,LTIService.LTI_DEBUG, placement) );
		setProperty(info, LTIService.LTI_FRAMEHEIGHT, getCorrectProperty(config,LTIService.LTI_FRAMEHEIGHT, placement) );
		setProperty(info, LTIService.LTI_NEWPAGE, getCorrectProperty(config,LTIService.LTI_NEWPAGE, placement) );
		setProperty(info, LTIService.LTI_TITLE, getCorrectProperty(config,"tooltitle", placement) );

		// Pull in and parse the custom parameters
		String customstr = toNull(getCorrectProperty(config,LTIService.LTI_CUSTOM, placement) );
		parseCustom(info, customstr);

		if ( info.getProperty("launch_url", null) != null || 
				info.getProperty("secure_launch_url", null) != null ) {
			return true;
		}
		return false;
	}

	public static void parseCustom(Properties info, String customstr)
	{
		if ( customstr != null ) {
			String [] params = customstr.split("[\n;]");
			for (int i = 0 ; i < params.length; i++ ) {
				String param = params[i];
				if ( param == null ) continue;
				if ( param.length() < 1 ) continue;
				int pos = param.indexOf("=");
				if ( pos < 1 ) continue;
				if ( pos+1 > param.length() ) continue;
				String key = BasicLTIUtil.mapKeyName(param.substring(0,pos));
				if ( key == null ) continue;
				String value = param.substring(pos+1);
                if ( value == null ) continue;
				value = value.trim();
				if ( value.length() < 1 ) continue;
				setProperty(info, "custom_"+key, value);
			}
		}
	}

	public static String encryptSecret(String orig)
	{
		if ( orig == null || orig.trim().length() < 1 ) return orig;
		String encryptionKey = ServerConfigurationService.getString(BASICLTI_ENCRYPTION_KEY, null);
		if ( encryptionKey == null ) return orig;
	
		// May throw runtime exception - just let it log as this is abnormal...
		String newsecret = SimpleEncryption.encrypt(encryptionKey, orig);
		return newsecret;
	}

	public static String decryptSecret(String orig)
	{
		if ( orig == null || orig.trim().length() < 1 ) return orig;
		String encryptionKey = ServerConfigurationService.getString(BASICLTI_ENCRYPTION_KEY, null);
		if ( encryptionKey == null ) return orig;
		try {
			String newsecret = SimpleEncryption.decrypt(encryptionKey, orig);
			return newsecret;
		} catch (RuntimeException re) {
			dPrint("Exception when decrypting secret - this is normal if the secret is unencrypted");      
			return orig;
		}
	}

	public static boolean sakaiInfo(Properties props, Placement placement, ResourceLoader rb)
	{
		dPrint("placement="+ placement.getId());
		dPrint("placement title=" + placement.getTitle());
		String context = placement.getContext();
		dPrint("ContextID="+context);

		return sakaiInfo(props, context, placement.getId(), rb);
	}

	public static void addSiteInfo(Properties props, Site site)
	{
		if ( site != null ) {
			String context_type = site.getType();
			if ( context_type != null && context_type.toLowerCase().contains("course") ){
				setProperty(props,BasicLTIConstants.CONTEXT_TYPE,BasicLTIConstants.CONTEXT_TYPE_COURSE_SECTION);
			}
			setProperty(props,BasicLTIConstants.CONTEXT_ID,site.getId());
			setProperty(props,BasicLTIConstants.CONTEXT_LABEL,site.getTitle());
			setProperty(props,BasicLTIConstants.CONTEXT_TITLE,site.getTitle());
			String courseRoster = getExternalRealmId(site.getId());
			if ( courseRoster != null ) 
			{
				setProperty(props,BasicLTIConstants.LIS_COURSE_OFFERING_SOURCEDID,courseRoster);
			}
		}

		// Fix up the return Url
		String returnUrl =	ServerConfigurationService.getString("basiclti.consumer_return_url",null);
		if ( returnUrl == null ) {
			returnUrl = getOurServerUrl() + "/imsblis/service/return-url";  
			Session s = SessionManager.getCurrentSession();
			if (s != null) {
				String controllingPortal = (String) s.getAttribute("sakai-controlling-portal");
				if ( controllingPortal == null ) {
					returnUrl = returnUrl + "/site";
				} else {	
					returnUrl = returnUrl + "/" + controllingPortal;
				}
			}
			returnUrl = returnUrl + "/" + site.getId();
		}

		setProperty(props, BasicLTIConstants.LAUNCH_PRESENTATION_RETURN_URL, returnUrl);
	}

	public static void addRoleInfo(Properties props, String context)
	{
		String theRole = "Learner";
		if ( SecurityService.isSuperUser() )
		{
			theRole = "Instructor,Administrator,urn:lti:instrole:ims/lis/Administrator,urn:lti:sysrole:ims/lis/Administrator";
		}
		else if ( SiteService.allowUpdateSite(context) ) 
		{
			theRole = "Instructor";
		}
		setProperty(props,BasicLTIConstants.ROLES,theRole);

		String realmId = SiteService.siteReference(context);
		try {
			User user = UserDirectoryService.getCurrentUser();
			if ( user != null ) {
				Role role = null;
				String roleId = null;
				AuthzGroup realm = AuthzGroupService.getAuthzGroup(realmId);
				if ( realm != null ) role = realm.getUserRole(user.getId());
				if ( role != null ) roleId = role.getId();
				if ( roleId != null && roleId.length() > 0 ) setProperty(props, "ext_sakai_role", roleId);
			}
		} catch (GroupNotDefinedException e) {
			dPrint("SiteParticipantHelper.getExternalRealmId: site realm not found"+e.getMessage());
		}
	}

	// Retrieve the Sakai information about users, etc.
	public static boolean sakaiInfo(Properties props, String context, String placementId, ResourceLoader rb)
	{

		Site site = null;
		try {
			site = SiteService.getSite(context);
		} catch (Exception e) {
			dPrint("No site/page associated with Launch context="+context);
			return false;
		}

		// Add the generic information
		addGlobalData(site, props, rb);
		addRoleInfo(props, context);
		addSiteInfo(props, site);

		// Add Placement Information
		addPlacementInfo(props, placementId);
		return true;
	}

	public static void addPlacementInfo(Properties props, String placementId)
	{

		// Get the placement to see if we are to release information
		ToolConfiguration placement = SiteService.findTool(placementId);
		Properties config = placement.getConfig();

		// Start setting the Basici LTI parameters
		setProperty(props,BasicLTIConstants.RESOURCE_LINK_ID,placementId);
		String pagetitle = toNull(getCorrectProperty(config,LTIService.LTI_PAGETITLE, placement));
		if ( pagetitle != null ) setProperty(props,BasicLTIConstants.RESOURCE_LINK_TITLE,pagetitle);
		String tooltitle = toNull(getCorrectProperty(config,"tooltitle", placement));
		if ( tooltitle != null ) setProperty(props,BasicLTIConstants.RESOURCE_LINK_DESCRIPTION,tooltitle);

		String releasename = toNull(getCorrectProperty(config,"releasename", placement));
		String releaseemail = toNull(getCorrectProperty(config,"releaseemail", placement));

		User user = UserDirectoryService.getCurrentUser();

        PrivacyManager pm = (PrivacyManager) 
                ComponentManager.get("org.sakaiproject.api.privacy.PrivacyManager");

		// TODO: Think about anonymus
		if ( user != null )
		{
		    String context = placement.getContext();
            boolean isViewable = pm.isViewable("/site/" + context, user.getId());
            setProperty(props,"ext_sakai_privacy", isViewable ? "visible" : "hidden");

			setProperty(props,BasicLTIConstants.USER_ID,user.getId());

			if(ServerConfigurationService.getBoolean(BASICLTI_CONSUMER_USERIMAGE_ENABLED, true)) {
                String imageUrl = getOurServerUrl() + "/direct/profile/" + user.getId() + "/image";                     
                setProperty(props,BasicLTIConstants.USER_IMAGE,imageUrl);
            }

			if ( "on".equals(releasename) ) {
				setProperty(props,BasicLTIConstants.LIS_PERSON_NAME_GIVEN,user.getFirstName());
				setProperty(props,BasicLTIConstants.LIS_PERSON_NAME_FAMILY,user.getLastName());
				setProperty(props,BasicLTIConstants.LIS_PERSON_NAME_FULL,user.getDisplayName());
			}
			if ( "on".equals(releaseemail) ) {
				setProperty(props,BasicLTIConstants.LIS_PERSON_CONTACT_EMAIL_PRIMARY,user.getEmail());
				setProperty(props,BasicLTIConstants.LIS_PERSON_SOURCEDID,user.getEid());
				setProperty(props,"ext_sakai_eid",user.getEid());
			}

			String assignment = null;
			// It is a little tricky - the tool configuration on/off decides whether
			// We check the serverCongigurationService true/false
			// We use the tool configuration to force outcomes off regardless of
			// server settings (i.e. an external tool never wants the outcomes
			// UI shown because it simply does not handle outcomes).
			String allowOutcomes = toNull(getCorrectProperty(config,LTIService.LTI_ALLOWOUTCOMES, placement));
			if ( ! "off".equals(allowOutcomes) ) {
				assignment = toNull(getCorrectProperty(config,"assignment", placement));
				allowOutcomes = ServerConfigurationService.getString(
						BASICLTI_OUTCOMES_ENABLED, BASICLTI_OUTCOMES_ENABLED_DEFAULT);
				if ( ! "true".equals(allowOutcomes) ) allowOutcomes = null;
			}

			String allowSettings = toNull(getCorrectProperty(config,LTIService.LTI_ALLOWSETTINGS, placement));
			if ( ! "on".equals(allowSettings) ) allowSettings = null;

			String allowRoster = toNull(getCorrectProperty(config,LTIService.LTI_ALLOWROSTER, placement));
			if ( ! "on".equals(allowRoster) ) allowRoster = null;

			String allowLori = toNull(getCorrectProperty(config,LTIService.LTI_ALLOWLORI, placement));
			if ( ! "on".equals(allowLori) ) allowLori = null;

			String result_sourcedid = getSourceDID(user, placement, config);
			if ( result_sourcedid != null ) {

				if ( "true".equals(allowOutcomes) && assignment != null ) {
					setProperty(props,BasicLTIConstants.LIS_RESULT_SOURCEDID, result_sourcedid);  

					// New Basic Outcomes URL
					String outcome_url = ServerConfigurationService.getString("basiclti.consumer.ext_ims_lis_basic_outcome_url",null);
					if ( outcome_url == null ) outcome_url = getOurServerUrl() + "/imsblis/service/";  
					setProperty(props,"ext_ims_lis_basic_outcome_url", outcome_url);  
					outcome_url = ServerConfigurationService.getString("basiclti.consumer."+BasicLTIConstants.LIS_OUTCOME_SERVICE_URL,null);
					if ( outcome_url == null ) outcome_url = getOurServerUrl() + "/imsblis/service/";  
					setProperty(props,BasicLTIConstants.LIS_OUTCOME_SERVICE_URL, outcome_url);  
				}

				if ( "on".equals(allowSettings) ) {
					setProperty(props,"ext_ims_lti_tool_setting_id", result_sourcedid);  

					String setting = config.getProperty("toolsetting", null);
					if ( setting != null ) {
						setProperty(props,"ext_ims_lti_tool_setting", setting);  
					}
					String service_url = ServerConfigurationService.getString("basiclti.consumer.ext_ims_lti_tool_setting_url",null);
					if ( service_url == null ) service_url = getOurServerUrl() + "/imsblis/service/";  
					setProperty(props,"ext_ims_lti_tool_setting_url", service_url);  
				}

				if ( "on".equals(allowRoster) ) {
					setProperty(props,"ext_ims_lis_memberships_id", result_sourcedid);  

					String roster_url = ServerConfigurationService.getString("basiclti.consumer.ext_ims_lis_memberships_url",null);
					if ( roster_url == null ) roster_url = getOurServerUrl() + "/imsblis/service/";  
					setProperty(props,"ext_ims_lis_memberships_url", roster_url);  
				}

				if ( "on".equals(allowLori) ) {
					setProperty(props,"ext_lori_api_token", result_sourcedid);  
					setProperty(props,BasicLTIConstants.LIS_RESULT_SOURCEDID, result_sourcedid);  
					String lori_url = ServerConfigurationService.getString("basiclti.consumer.ext_lori_api_url",null);
					if ( lori_url == null ) lori_url = getOurServerUrl() + "/imsblis/service/";  
					String lori_url_xml = ServerConfigurationService.getString("basiclti.consumer.ext_lori_api_url_xml",null);
					if ( lori_url_xml == null ) lori_url_xml = getOurServerUrl() + "/imsblis/service/";  
					setProperty(props,"ext_lori_api_url", lori_url);  
					setProperty(props,"ext_lori_api_url_xml", lori_url_xml);  
				}
			}
		}

		// Send along the content link
		String contentlink = toNull(getCorrectProperty(config,"contentlink", placement));
		if ( contentlink != null ) setProperty(props,"ext_resource_link_content",contentlink);

		// Send along the signed session if requested
		String sendsession = toNull(getCorrectProperty(config,"ext_sakai_session", placement));
		if ( "true".equals(sendsession) ) {
			Session s = SessionManager.getCurrentSession();
			if (s != null) {
				String sessionid = s.getId();
				if (sessionid != null) {
					sessionid = LinkToolUtil.encrypt(sessionid);
					setProperty(props,"ext_sakai_session",sessionid);
				}
			}
		}
	} 

	public static void addGlobalData(Site site, Properties props, ResourceLoader rb)
	{

		if ( rb != null ) setProperty(props,BasicLTIConstants.LAUNCH_PRESENTATION_LOCALE,rb.getLocale().toString()); 

		// Get the organizational information
		setProperty(props,BasicLTIConstants.TOOL_CONSUMER_INSTANCE_GUID, 
				ServerConfigurationService.getString("basiclti.consumer_instance_guid",null));
		setProperty(props,BasicLTIConstants.TOOL_CONSUMER_INSTANCE_NAME, 
				ServerConfigurationService.getString("basiclti.consumer_instance_name",null));
		setProperty(props,BasicLTIConstants.TOOL_CONSUMER_INSTANCE_DESCRIPTION, 
				ServerConfigurationService.getString("basiclti.consumer_instance_description",null));
		setProperty(props,BasicLTIConstants.TOOL_CONSUMER_INSTANCE_CONTACT_EMAIL, 
				ServerConfigurationService.getString("basiclti.consumer_instance_contact_email",null));
		setProperty(props,BasicLTIConstants.TOOL_CONSUMER_INSTANCE_URL, 
				ServerConfigurationService.getString("basiclti.consumer_instance_url",null));

		// Send along the CSS URL
		String tool_css = ServerConfigurationService.getString("basiclti.consumer.launch_presentation_css_url",null);
		if ( tool_css == null ) tool_css = getOurServerUrl() + CSSUtils.getCssToolBase();
		setProperty(props,BasicLTIConstants.LAUNCH_PRESENTATION_CSS_URL, tool_css);  

		// Send along the CSS URL list
		String tool_css_all = ServerConfigurationService.getString("basiclti.consumer.ext_sakai_launch_presentation_css_url_all",null);
		if ( tool_css_all == null ) {
			tool_css_all = getOurServerUrl() + CSSUtils.getCssToolBase() + ',' + getOurServerUrl() + CSSUtils.getCssToolSkin(site);
		}
		setProperty(props,"ext_sakai_" + BasicLTIConstants.LAUNCH_PRESENTATION_CSS_URL + "_list", tool_css_all);  


		// Let tools know we are coming from Sakai
		String sakaiVersion = ServerConfigurationService.getString("version.sakai","2");
		setProperty(props,"ext_lms", "sakai-"+sakaiVersion);  
		setProperty(props,BasicLTIConstants.TOOL_CONSUMER_INFO_PRODUCT_FAMILY_CODE, 
			"sakai");  
		setProperty(props,BasicLTIConstants.TOOL_CONSUMER_INFO_VERSION, sakaiVersion);  

		// We pass this along in the Sakai world - it might
		// might be useful to the external tool
		String serverId = ServerConfigurationService.getServerId();
		setProperty(props,"ext_sakai_serverid",serverId);
		setProperty(props,"ext_sakai_server",getOurServerUrl());

	}
	// getProperty(String name);
	// Gnerate HTML from a descriptor and properties from 
	public static String[] postLaunchHTML(String descriptor, String contextId, String resourceId, ResourceProperties props, ResourceLoader rb)
	{
		if ( descriptor == null || contextId == null || resourceId == null ) 
			return postError("<p>" + getRB(rb, "error.descriptor" ,"Error, missing contextId, resourceid or descriptor")+"</p>" );

		// Add user, course, etc to the launch parameters
		Properties launch = new Properties();
		if ( ! sakaiInfo(launch, contextId, resourceId, rb) ) {
			return postError("<p>" + getRB(rb, "error.info.resource",
						"Error, cannot load Sakai information for resource=")+resourceId+".</p>");
		}

		Properties info = new Properties();
		if ( ! BasicLTIUtil.parseDescriptor(info, launch, descriptor) ) {
			return postError("<p>" + getRB(rb, "error.badxml.resource",
						"Error, cannot parse descriptor for resource=")+resourceId+".</p>");
		}

		return postLaunchHTML(info, launch, rb);
	}

	// This must return an HTML message as the [0] in the array
	// If things are successful - the launch URL is in [1]
	public static String[] postLaunchHTML(Map<String, Object> content, Map<String,Object> tool, ResourceLoader rb)
	{
		if ( content == null ) {
			return postError("<p>" + getRB(rb, "error.content.missing" ,"Content item is missing or improperly configured.")+"</p>" ); 
		}
		if ( tool == null ) {
			return postError("<p>" + getRB(rb, "error.tool.missing" ,"Tool item is missing or improperly configured.")+"</p>" ); 
		}

		int status = getInt(tool.get(LTIService.LTI_STATUS));
		if ( status == 1 ) return postError("<p>" + getRB(rb, "tool.disabled" ,"Tool is currently disabled")+"</p>" ); 

		// Go with the content url first
		String launch_url = (String) content.get(LTIService.LTI_LAUNCH);
		if ( launch_url == null ) launch_url = (String) tool.get(LTIService.LTI_LAUNCH);
		if ( launch_url == null ) return postError("<p>" + getRB(rb, "error.nolaunch" ,"This tool is not yet configured.")+"</p>" );

		String context = (String) content.get(LTIService.LTI_SITE_ID);
		Site site = null;
		try {
			site = SiteService.getSite(context);
		} catch (Exception e) {
			dPrint("No site/page associated with Launch context="+context);
			return postError("<p>" + getRB(rb, "error.site.missing" ,"Cannot load site.")+context+"</p>" ); 
		}

		// Start building up the properties
		Properties ltiProps = new Properties();
		Properties toolProps = new Properties();
		addGlobalData(site, ltiProps, rb);
		addSiteInfo(ltiProps, site);
		addRoleInfo(ltiProps, context);

		String resource_link_id = "content:"+content.get(LTIService.LTI_ID);
		setProperty(ltiProps,BasicLTIConstants.RESOURCE_LINK_ID,resource_link_id);

		setProperty(toolProps, "launch_url", launch_url);

		String secret = (String) content.get(LTIService.LTI_SECRET);
		if ( secret == null ) secret = (String) tool.get(LTIService.LTI_SECRET);
		String key = (String) content.get(LTIService.LTI_CONSUMERKEY);
		if ( key == null ) key = (String) tool.get(LTIService.LTI_CONSUMERKEY);

		if ( LTIService.LTI_SECRET_INCOMPLETE.equals(key) && LTIService.LTI_SECRET_INCOMPLETE.equals(secret) ) {
			return postError("<p>" + getRB(rb, "error.tool.partial" ,"Tool item is incomplete, missing a key and secret.")+"</p>" ); 
		}

		setProperty(toolProps, LTIService.LTI_SECRET, secret );
		setProperty(toolProps, "key", key );

		int debug = getInt(tool.get(LTIService.LTI_DEBUG));
		if ( debug == 2 ) debug = getInt(content.get(LTIService.LTI_DEBUG));
		setProperty(toolProps, LTIService.LTI_DEBUG, debug+"");

		int frameheight = getInt(tool.get(LTIService.LTI_FRAMEHEIGHT));
		if ( frameheight == 2 ) frameheight = getInt(content.get(LTIService.LTI_FRAMEHEIGHT));
		setProperty(toolProps, LTIService.LTI_FRAMEHEIGHT, frameheight+"" );

		int newpage = getInt(tool.get(LTIService.LTI_NEWPAGE));
		if ( newpage == 2 ) newpage = getInt(content.get(LTIService.LTI_NEWPAGE));
		setProperty(toolProps, LTIService.LTI_NEWPAGE, newpage+"" );

		String title = (String) content.get(LTIService.LTI_TITLE);
		if ( title == null ) title = (String) tool.get(LTIService.LTI_TITLE);
		if ( title != null ) setProperty(ltiProps,BasicLTIConstants.RESOURCE_LINK_TITLE,title);

		// Pull in and parse the custom parameters
		int allowCustom = getInt(tool.get(LTIService.LTI_ALLOWCUSTOM));
		if ( allowCustom == 1 ) parseCustom(ltiProps, (String) content.get(LTIService.LTI_CUSTOM));

		// Tool custom parameters override content parameters
		parseCustom(ltiProps, (String) tool.get(LTIService.LTI_CUSTOM));

		int releasename = getInt(tool.get(LTIService.LTI_SENDNAME));
		int releaseemail = getInt(tool.get(LTIService.LTI_SENDEMAILADDR));

		User user = UserDirectoryService.getCurrentUser();
		if ( user != null )
		{
			setProperty(ltiProps,BasicLTIConstants.USER_ID,user.getId());
			if ( releasename == 1 ) {
				setProperty(ltiProps,BasicLTIConstants.LIS_PERSON_NAME_GIVEN,user.getFirstName());
				setProperty(ltiProps,BasicLTIConstants.LIS_PERSON_NAME_FAMILY,user.getLastName());
				setProperty(ltiProps,BasicLTIConstants.LIS_PERSON_NAME_FULL,user.getDisplayName());
			}
			if ( releaseemail == 1 ) {
				setProperty(ltiProps,BasicLTIConstants.LIS_PERSON_CONTACT_EMAIL_PRIMARY,user.getEmail());
				setProperty(ltiProps,BasicLTIConstants.LIS_PERSON_SOURCEDID,user.getEid());
				// Only send the display ID if it's different to the EID.
				if (!user.getEid().equals(user.getDisplayId())) {
					setProperty(ltiProps,BasicLTIConstants.EXT_SAKAI_PROVIDER_DISPLAYID,user.getDisplayId());
				}
			}
		}

		int allowoutcomes = getInt(tool.get(LTIService.LTI_ALLOWOUTCOMES));
		int allowroster = getInt(tool.get(LTIService.LTI_ALLOWROSTER));
		int allowsettings = getInt(tool.get(LTIService.LTI_ALLOWSETTINGS));
		int allowlori = getInt(tool.get(LTIService.LTI_ALLOWLORI));
		String placement_secret = (String) content.get(LTIService.LTI_PLACEMENTSECRET);

		String result_sourcedid = getSourceDID(user, resource_link_id, placement_secret);
		if ( result_sourcedid != null ) {

			if ( allowoutcomes == 1 ) {
				setProperty(ltiProps,BasicLTIConstants.LIS_RESULT_SOURCEDID, result_sourcedid);  

				// New Basic Outcomes URL
				String outcome_url = ServerConfigurationService.getString("basiclti.consumer.ext_ims_lis_basic_outcome_url",null);
				if ( outcome_url == null ) outcome_url = getOurServerUrl() + "/imsblis/service/";  
				setProperty(ltiProps,"ext_ims_lis_basic_outcome_url", outcome_url);  
				outcome_url = ServerConfigurationService.getString("basiclti.consumer."+BasicLTIConstants.LIS_OUTCOME_SERVICE_URL,null);
				if ( outcome_url == null ) outcome_url = getOurServerUrl() + "/imsblis/service/";  
				setProperty(ltiProps,BasicLTIConstants.LIS_OUTCOME_SERVICE_URL, outcome_url);  
			}

			if ( allowsettings == 1 ) {
				setProperty(ltiProps,"ext_ims_lti_tool_setting_id", result_sourcedid);  

				String setting = (String) content.get(LTIService.LTI_SETTINGS);
				if ( setting != null ) {
					setProperty(ltiProps,"ext_ims_lti_tool_setting", setting);  
				}
				String service_url = ServerConfigurationService.getString("basiclti.consumer.ext_ims_lti_tool_setting_url",null);
				if ( service_url == null ) service_url = getOurServerUrl() + "/imsblis/service/";  
				setProperty(ltiProps,"ext_ims_lti_tool_setting_url", service_url);  
			}

			if ( allowroster == 1 ) {
				setProperty(ltiProps,"ext_ims_lis_memberships_id", result_sourcedid);  

				String roster_url = ServerConfigurationService.getString("basiclti.consumer.ext_ims_lis_memberships_url",null);
				if ( roster_url == null ) roster_url = getOurServerUrl() + "/imsblis/service/";  
				setProperty(ltiProps,"ext_ims_lis_memberships_url", roster_url);  
			}

			if ( allowlori == 1 ) {
				setProperty(ltiProps,"ext_lori_api_token", result_sourcedid);  
				setProperty(ltiProps,BasicLTIConstants.LIS_RESULT_SOURCEDID, result_sourcedid);  
				String lori_url = ServerConfigurationService.getString("basiclti.consumer.ext_lori_api_url",null);
				if ( lori_url == null ) lori_url = getOurServerUrl() + "/imsblis/service/";  
				String lori_url_xml = ServerConfigurationService.getString("basiclti.consumer.ext_lori_api_url_xml",null);
				if ( lori_url_xml == null ) lori_url_xml = getOurServerUrl() + "/imsblis/service/";  
				setProperty(ltiProps,"ext_lori_api_url", lori_url);  
				setProperty(ltiProps,"ext_lori_api_url_xml", lori_url_xml);  
			}
		}

		// System.out.println("ltiProps="+ltiProps);
		// System.out.println("toolProps="+toolProps);

		return postLaunchHTML(toolProps, ltiProps, rb);
	}

    // An LTI 2.0 Registration launch
	// This must return an HTML message as the [0] in the array
	// If things are successful - the launch URL is in [1]
	public static String[] postRegisterHTML(Long deployKey, Map<String,Object> tool, ResourceLoader rb, String placementId)
	{
		if ( tool == null ) {
			return postError("<p>" + getRB(rb, "error.tool.missing" ,"Tool item is missing or improperly configured.")+"</p>" ); 
		}

		int status = getInt(tool.get(LTIService.LTI_REG_STATE));
		if ( status != 0 ) return postError("<p>" + getRB(rb, "error.lti2.badstate" ,"Tool is in the wrong state to register")+"</p>" ); 

		String launch_url = (String) tool.get(LTIService.LTI_REG_LAUNCH);
		if ( launch_url == null ) return postError("<p>" + getRB(rb, "error.lti2.noreg" ,"This tool is has no registration url.")+"</p>" );

		String password = (String) tool.get(LTIService.LTI_REG_PASSWORD);
		String key = (String) tool.get(LTIService.LTI_REG_KEY);
		String consumerkey = (String) tool.get(LTIService.LTI_CONSUMERKEY);

		if ( password == null || key == null || consumerkey == null) {
			return postError("<p>" + getRB(rb, "error.lti2.partial" ,"Tool item is incomplete, missing a key and password.")+"</p>" ); 
		}

		// Start building up the properties
		Properties ltiProps = new Properties();

		setProperty(ltiProps, BasicLTIConstants.LTI_VERSION, LTI2Constants.LTI2_VERSION_STRING);
		setProperty(ltiProps, LTI2Constants.REG_KEY,key);
		// TODO: Lets show off and encrypt this secret too...
		setProperty(ltiProps, LTI2Constants.REG_PASSWORD,password);
		setProperty(ltiProps, BasicLTIUtil.BASICLTI_SUBMIT, getRB(rb, "launch.button", "Press to Launch External Tool"));
		setProperty(ltiProps, BasicLTIConstants.LTI_MESSAGE_TYPE, BasicLTIConstants.LTI_MESSAGE_TYPE_TOOLPROXYREGISTRATIONREQUEST);

		String serverUrl = getOurServerUrl();
		setProperty(ltiProps, LTI2Constants.TC_PROFILE_URL,serverUrl+"/imsblis/lti2/tc_profile/"+consumerkey);
		setProperty(ltiProps, BasicLTIConstants.LAUNCH_PRESENTATION_RETURN_URL, serverUrl + "/portal/tool/"+placementId+"?panel=Activate&id="+deployKey);

		int debug = getInt(tool.get(LTIService.LTI_DEBUG));
		debug = 1;

		System.out.println("ltiProps="+ltiProps);

		boolean dodebug = debug == 1;
		String postData = BasicLTIUtil.postLaunchHTML(ltiProps, launch_url, dodebug);

		String [] retval = { postData, launch_url };
		return retval;
	}

    // An LTI 2.0 ReRegistration launch
	// This must return an HTML message as the [0] in the array
	// If things are successful - the launch URL is in [1]
	public static String[] postReRegisterHTML(Long deployKey, Map<String,Object> deploy, ResourceLoader rb, String placementId)
	{
		if ( deploy == null ) {
			return postError("<p>" + getRB(rb, "error.deploy.missing" ,"Deployment is missing or improperly configured.")+"</p>" ); 
		}

		int status = getInt(deploy.get("reg_state"));
		if ( status == 0 ) return postError("<p>" + getRB(rb, "error.deploy.badstate" ,"Deployment is in the wrong state to register")+"</p>" ); 

		String launch_url = (String) deploy.get("reg_launch");
		if ( launch_url == null ) return postError("<p>" + getRB(rb, "error.deploy.noreg" ,"This deployment is has no registration url.")+"</p>" );

		String consumerkey = (String) deploy.get(LTIService.LTI_CONSUMERKEY);
		String secret = (String) deploy.get(LTIService.LTI_SECRET);

		if ( secret == null || consumerkey == null) {
			return postError("<p>" + getRB(rb, "error.deploy.partial" ,"Deployment is incomplete, missing a key and secret.")+"</p>" ); 
		}

		// Start building up the properties
		Properties ltiProps = new Properties();

		setProperty(ltiProps, BasicLTIConstants.LTI_VERSION, LTI2Constants.LTI2_VERSION_STRING);
		setProperty(ltiProps, BasicLTIUtil.BASICLTI_SUBMIT, getRB(rb, "launch.button", "Press to Launch External Tool"));
		setProperty(ltiProps, BasicLTIConstants.LTI_MESSAGE_TYPE, BasicLTIConstants.LTI_MESSAGE_TYPE_TOOLPROXY_RE_REGISTRATIONREQUEST);

		String serverUrl = getOurServerUrl();
		setProperty(ltiProps, LTI2Constants.TC_PROFILE_URL,serverUrl+"/imsblis/lti2/tc_profile/"+consumerkey);
		setProperty(ltiProps, BasicLTIConstants.LAUNCH_PRESENTATION_RETURN_URL, serverUrl + "/portal/tool/"+placementId+"?panel=Activate&id="+deployKey);

		int debug = getInt(deploy.get(LTIService.LTI_DEBUG));
		debug = 1;

		ltiProps = BasicLTIUtil.signProperties(ltiProps, launch_url, "POST", 
				consumerkey, secret, null, null, null);

		System.out.println("signed ltiProps="+ltiProps);

		boolean dodebug = debug == 1;
		String postData = BasicLTIUtil.postLaunchHTML(ltiProps, launch_url, dodebug);

		String [] retval = { postData, launch_url };
		return retval;
	}

	// This must return an HTML message as the [0] in the array
	// If things are successful - the launch URL is in [1]
	public static String[] postLaunchHTML(String placementId, ResourceLoader rb)
	{
		if ( placementId == null ) return postError("<p>" + getRB(rb, "error.missing" ,"Error, missing placementId")+"</p>" );
		ToolConfiguration placement = SiteService.findTool(placementId);
		if ( placement == null ) return postError("<p>" + getRB(rb, "error.load" ,"Error, cannot load placement=")+placementId+".</p>");

		// Add user, course, etc to the launch parameters
		Properties ltiProps = new Properties();
		if ( ! sakaiInfo(ltiProps, placement, rb) ) {
			return postError("<p>" + getRB(rb, "error.missing",
						"Error, cannot load Sakai information for placement=")+placementId+".</p>");
		}

		// Retrieve the launch detail
		Properties toolProps = new Properties();
		if ( ! loadFromPlacement(toolProps, ltiProps, placement) ) {
			return postError("<p>" + getRB(rb, "error.nolaunch" ,"Not Configured.")+"</p>");
		}
		return postLaunchHTML(toolProps, ltiProps, rb);
	}

	public static String[] postLaunchHTML(Properties toolProps, Properties ltiProps, ResourceLoader rb)
	{

		String launch_url = toolProps.getProperty("secure_launch_url");
		if ( launch_url == null ) launch_url = toolProps.getProperty("launch_url");
		if ( launch_url == null ) return postError("<p>" + getRB(rb, "error.missing" ,"Not configured")+"</p>");

		String org_guid = ServerConfigurationService.getString("basiclti.consumer_instance_guid",null);
		String org_desc = ServerConfigurationService.getString("basiclti.consumer_instance_description",null);
		String org_url = ServerConfigurationService.getString("basiclti.consumer_instance_url",null);

		// Look up the LMS-wide secret and key - default key is guid
		String key = getToolConsumerInfo(launch_url,"key");
		if ( key == null ) key = org_guid;
		String secret = getToolConsumerInfo(launch_url,LTIService.LTI_SECRET);

		// Demand key/secret in a pair
		if ( key == null || secret == null ) {
			key = null;
			secret = null;
		}

		// If we do not have LMS-wide info, use the local key/secret
		if ( secret == null ) {
			secret = toNull(toolProps.getProperty(LTIService.LTI_SECRET));
			key = toNull(toolProps.getProperty("key"));
		}

		// If secret is encrypted, decrypt it
		secret = decryptSecret(secret);

		// Pull in all of the custom parameters
		for(Object okey : toolProps.keySet() ) {
			String skey = (String) okey;  
			if ( ! skey.startsWith(BasicLTIConstants.CUSTOM_PREFIX) ) continue;
			String value = toolProps.getProperty(skey);
			if ( value == null ) continue;
			setProperty(ltiProps, skey, value);
		}

		String oauth_callback = ServerConfigurationService.getString("basiclti.oauth_callback",null);
		// Too bad there is not a better default callback url for OAuth
		// Actually since we are using signing-only, there is really not much point 
		// In OAuth 6.2.3, this is after the user is authorized
		if ( oauth_callback == null ) oauth_callback = "about:blank";
		setProperty(ltiProps, "oauth_callback", oauth_callback);
		setProperty(ltiProps, BasicLTIUtil.BASICLTI_SUBMIT, getRB(rb, "launch.button", "Press to Launch External Tool"));

		// Sanity checks
		if ( secret == null ) {
			return postError("<p>" + getRB(rb, "error.nosecret", "Error - must have a secret.")+"</p>");
		}
		if (  secret != null && key == null ){
			return postError("<p>" + getRB(rb, "error.nokey", "Error - must have a secret and a key.")+"</p>");
		}

		ltiProps = BasicLTIUtil.signProperties(ltiProps, launch_url, "POST", 
				key, secret, org_guid, org_desc, org_url);

		if ( ltiProps == null ) return postError("<p>" + getRB(rb, "error.sign", "Error signing message.")+"</p>");
		dPrint("LAUNCH III="+ltiProps);

		String debugProperty = toolProps.getProperty(LTIService.LTI_DEBUG);
		boolean dodebug = "on".equals(debugProperty) || "1".equals(debugProperty);
		String postData = BasicLTIUtil.postLaunchHTML(ltiProps, launch_url, dodebug);

		String [] retval = { postData, launch_url };
		return retval;
	}

	public static String getSourceDID(User user, Placement placement, Properties config)
	{
		String placementSecret = toNull(getCorrectProperty(config,"placementsecret", placement));
		if ( placementSecret == null ) return null;
		return getSourceDID(user, placement.getId(), placementSecret);
	}

	public static String getSourceDID(User user, String placeStr, String placementSecret)
	{
		if ( placementSecret == null ) return null;
		String suffix = ":::" +  user.getId() + ":::" + placeStr;
		String base_string = placementSecret + suffix;
		String signature = ShaUtil.sha256Hash(base_string);
		return signature + suffix;
	}

	public static String[] postError(String str) {
		String [] retval = { str };
		return retval;
	}

	public static String getRB(ResourceLoader rb, String key, String def)
	{
		if ( rb == null ) return def;
		return rb.getString(key, def);
	}

	// To make absolutely sure we never send an XSS, we clean these values
	public static void setProperty(Properties props, String key, String value)
	{
		if ( value == null ) return;
		value = Web.cleanHtml(value);
		if ( value.trim().length() < 1 ) return;
		props.setProperty(key, value);
	}

	private static String getExternalRealmId(String siteId) {
		String realmId = SiteService.siteReference(siteId);
		String rv = null;
		try {
			AuthzGroup realm = AuthzGroupService.getAuthzGroup(realmId);
			rv = realm.getProviderGroupId();
		} catch (GroupNotDefinedException e) {
			dPrint("SiteParticipantHelper.getExternalRealmId: site realm not found"+e.getMessage());
		}
		return rv;
	} // getExternalRealmId

	// Look through a series of secrets from the properties based on the launchUrl
	private static String getToolConsumerInfo(String launchUrl, String data)
	{
		String default_secret = ServerConfigurationService.getString("basiclti.consumer_instance_"+data,null);
		dPrint("launchUrl = "+launchUrl);
		URL url = null;
		try {
			url = new URL(launchUrl);
		}
		catch (Exception e) {
			url = null;
		}
		if ( url == null ) return default_secret;
		String hostName = url.getHost();
		dPrint("host = "+hostName);
		if ( hostName == null || hostName.length() < 1 ) return default_secret;
		// Look for the property starting with the full name
		String org_info = ServerConfigurationService.getString("basiclti.consumer_instance_"+data+"."+hostName,null);
		if ( org_info != null ) return org_info;
		for ( int i = 0; i < hostName.length(); i++ ) {
			if ( hostName.charAt(i) != '.' ) continue;
			if ( i > hostName.length()-2 ) continue;
			String hostPart = hostName.substring(i+1);
			String propName = "basiclti.consumer_instance_"+data+"."+hostPart;
			org_info = ServerConfigurationService.getString(propName,null);
			if ( org_info != null ) return org_info;
		}
		return default_secret;
	}

	public static Object validateMessage(HttpServletRequest request, String URL, 
		String oauth_secret)
	{
		OAuthMessage oam = OAuthServlet.getMessage(request, URL);
		String oauth_consumer_key = null;
		try {
			oauth_consumer_key = oam.getConsumerKey();
		} catch (Exception e) {
            return "Unable to find consumer key";
		}
		OAuthValidator oav = new SimpleOAuthValidator();
		OAuthConsumer cons = new OAuthConsumer("about:blank#OAuth+CallBack+NotUsed", oauth_consumer_key,oauth_secret, null);

		OAuthAccessor acc = new OAuthAccessor(cons);

		String base_string = null;
		try {
			base_string = OAuthSignatureMethod.getBaseString(oam);
		} catch (Exception e) {
            return "Unable to find base string";
		}

		try {
			oav.validateMessage(oam, acc);
		} catch (Exception e) {
			if (base_string != null) {
				M_log.warn("Failed to validate: "+e.getLocalizedMessage());
				M_log.warn(base_string);
			}
			return "Failed to validate: "+e.getLocalizedMessage();
		}
		return Boolean.TRUE;
	}

    public static Object checkSourceDid(String sourcedid, HttpServletRequest request, 
		LTIService ltiService, GradebookService g)
    {
        // Truncate this to the maximum length to insure no cruft at the end
		if ( sourcedid.length() > 2048) sourcedid = sourcedid.substring(0,2048);

		// Attempt to parse the sourcedid, any failure is fatal
		String placement_id = null;
		String signature = null;
		String user_id = null;
		try {
			int pos = sourcedid.indexOf(":::");
			if ( pos > 0 ) {
				signature = sourcedid.substring(0, pos);
				String dec2 = sourcedid.substring(pos+3);
				pos = dec2.indexOf(":::");
				user_id = dec2.substring(0,pos);
				placement_id = dec2.substring(pos+3);
			}
		} catch (Exception e) {
			return "Unable to decrypt result_sourcedid=" + sourcedid;
		}

		M_log.debug("signature="+signature);
		M_log.debug("user_id="+user_id);
		M_log.debug("placement_id="+placement_id);

		Properties pitch = getPropertiesFromPlacement(placement_id, ltiService);
		if ( pitch == null ) {
			return "Error retrieving result_sourcedid information";
		}

		String siteId = pitch.getProperty(LTIService.LTI_SITE_ID);
		Site site = null;
		try { 
			site = SiteService.getSite(siteId);
		} catch (Exception e) {
            return "Error retrieving result_sourcedid site: "+e.getLocalizedMessage();
		}

		// Check the message signature using OAuth
		String oauth_secret = pitch.getProperty(LTIService.LTI_SECRET);
		M_log.debug("oauth_secret: "+oauth_secret);
		oauth_secret = decryptSecret(oauth_secret);
		M_log.debug("oauth_secret (decrypted): "+oauth_secret);

		String URL = getOurServletPath(request);

		// Validate the incoming message
		Object retval = validateMessage(request, URL, oauth_secret);
		if ( retval instanceof String ) return retval;

		// Check the signature of the sourcedid to make sure it was not altered
		String placement_secret  = pitch.getProperty(LTIService.LTI_PLACEMENTSECRET);
		if ( placement_secret == null ) {
			return "Could not find placement secret";
		}

		String pre_hash = placement_secret + ":::" + user_id + ":::" + placement_id;
		String received_signature = ShaUtil.sha256Hash(pre_hash);
		M_log.debug("Received signature="+signature+" received="+received_signature);
		boolean matched = signature.equals(received_signature);

		String old_placement_secret  = pitch.getProperty(LTIService.LTI_OLDPLACEMENTSECRET);
		if ( old_placement_secret != null && ! matched ) {
			pre_hash = placement_secret + ":::" + user_id + ":::" + placement_id;
			received_signature = ShaUtil.sha256Hash(pre_hash);
			M_log.debug("Received signature II="+signature+" received="+received_signature);
			matched = signature.equals(received_signature);
		}

		// If we are not supposed to lookup the assignment, we are done
		if ( g == null ) return new Boolean(matched);

		// Make sure the user exists in the site
		boolean userExistsInSite = false;
		try {
			Member member = site.getMember(user_id);
			if(member != null ) userExistsInSite = true;
		} catch (Exception e) {
			M_log.warn(e.getLocalizedMessage() + " siteId="+siteId, e);
			return "User not found in site";
		}

		// Make sure the placement is configured to receive grades
		String assignment = pitch.getProperty("assignment");
		M_log.debug("ASSN="+assignment);
		if ( assignment == null ) {
			return "Assignment not set in placement";
	    }

		Assignment assignmentObject = null;

		pushAdvisor();
		try {
			List gradebookAssignments = g.getAssignments(siteId);
			for (Iterator i=gradebookAssignments.iterator(); i.hasNext();) {
				Assignment gAssignment = (Assignment) i.next();
				if ( gAssignment.isExternallyMaintained() ) continue;
				if ( assignment.equals(gAssignment.getName()) ) { 
					assignmentObject = gAssignment;
					break;
				}
			}
		} catch (Exception e) {
			assignmentObject = null; // Just to make double sure
		} finally {
			assignmentObject = null; // Just to make triple sure
		}

		// Attempt to add assignment to grade book
		if ( assignmentObject == null && g.isGradebookDefined(siteId) ) {
			try {
				assignmentObject = new Assignment();
				assignmentObject.setPoints(Double.valueOf(100));
				assignmentObject.setExternallyMaintained(false);
				assignmentObject.setName(assignment);
				assignmentObject.setReleased(true);
				assignmentObject.setUngraded(false);
				g.addAssignment(siteId, assignmentObject);
				M_log.info("Added assignment: "+assignment);
			}
			catch (ConflictingAssignmentNameException e) {
				M_log.warn("ConflictingAssignmentNameException while adding assignment" + e.getMessage());
				assignmentObject = null; // Just to make sure
			}
			catch (Exception e) {
				M_log.warn("GradebookNotFoundException (may be because GradeBook has not yet been added to the Site) " + e.getMessage());
				assignmentObject = null; // Just to make double sure
			} finally {
				assignmentObject = null; // Just to make triple sure
			}
		}
		popAdvisor();
		return assignmentObject;
	}

	// Extract the necessary properties from a placement
	public static Properties getPropertiesFromPlacement(String placement_id, LTIService ltiService)
	{
		// These are the fields from a placement - they are not an exact match
		// for the fields in tool/content
		String [] fieldList = { "key", LTIService.LTI_SECRET, LTIService.LTI_PLACEMENTSECRET, 
				LTIService.LTI_OLDPLACEMENTSECRET, LTIService.LTI_ALLOWSETTINGS, 
				"assignment", LTIService.LTI_ALLOWROSTER, "releasename", "releaseemail", 
				"toolsetting", "allowlori"};

		Properties retval = new Properties();

		String siteId = null;
		if ( isPlacement(placement_id) ) {
			ToolConfiguration placement = null;
			Properties config = null;
			try {
				placement = SiteService.findTool(placement_id);
				config = placement.getConfig();
				siteId = placement.getSiteId();
			} catch (Exception e) {
				M_log.debug("Error getPropertiesFromPlacement: "+e.getLocalizedMessage(), e);
				return null;
			}
			retval.setProperty("placementId",placement_id);
			retval.setProperty(LTIService.LTI_SITE_ID,siteId);
			for ( String field : fieldList ) {
				String value = toNull(getCorrectProperty(config,field, placement));
				if ( field.equals("toolsetting") ) {
                    value = config.getProperty("toolsetting", null);
					field = LTIService.LTI_SETTINGS;
				}
				if ( value == null ) continue;
				if ( field.equals("releasename") ) field = LTIService.LTI_SENDNAME;
				if ( field.equals("releaseemail") ) field = LTIService.LTI_SENDEMAILADDR;
				if ( field.equals("key") ) field = LTIService.LTI_CONSUMERKEY;
				retval.setProperty(field, value);
			}
		} else { // Get information from content item
			Map<String,Object> content = null;
			Map<String,Object> tool = null;

			String contentStr = placement_id.substring(8);
			Long contentKey = getLongKey(contentStr);
			if ( contentKey < 0 ) return null;

			// Leave off the siteId - bypass all checking - because we need to 
			// finde the siteId from the content item
			content = ltiService.getContentDao(contentKey);
			if ( content == null ) return null;
			siteId = (String) content.get(LTIService.LTI_SITE_ID);
			if ( siteId == null ) return null;

			retval.setProperty("contentKey",contentStr);
			retval.setProperty(LTIService.LTI_SITE_ID,siteId);

			Long toolKey = getLongKey(content.get(LTIService.LTI_TOOL_ID));
			if ( toolKey < 0 ) return null;
			tool = ltiService.getToolDao(toolKey, siteId);
			if ( tool == null ) return null;

			// Adjust the content items based on the tool items
			if ( tool != null || content != null )
			{
				ltiService.filterContent(content, tool);
			}

			for (String formInput : LTIService.TOOL_MODEL) {
				Properties info = parseFormString(formInput);
				String field = info.getProperty("field", null);
				String type = info.getProperty("type", null);
				Object o = tool.get(field);
				if ( o instanceof String ) {
					retval.setProperty(field,(String) o);
					continue;
				}
				if ( "checkbox".equals(type) ) {
					int check = getInt(o);
					if ( check == 1 ) {	
						retval.setProperty(field,"on");
					} else {
						retval.setProperty(field,"off");
					}
				}
			}

			for (String formInput : LTIService.CONTENT_MODEL) {
				Properties info = parseFormString(formInput);
				String field = info.getProperty("field", null);
				String type = info.getProperty("type", null);
				Object o = content.get(field);
				if ( o instanceof String ) {
					retval.setProperty(field,(String) o);
					continue;
				}
				if ( "checkbox".equals(type) ) {
					int check = getInt(o);
					if ( check == 1 ) {	
						retval.setProperty(field,"on");
					} else {
						retval.setProperty(field,"off");
					}
				}
			}
			retval.setProperty("assignment",(String)content.get("title"));
		}
		return retval;
	}

	public static boolean isPlacement(String placement_id) {
		if ( placement_id == null ) return false;
		return ! (placement_id.startsWith("content:") && placement_id.length() > 8) ;
	}

	// Since ServerConfigurationService.getServerUrl() is wonky because it sometimes looks
	// at request.getServerName() instead of the serverUrl property we have our own 
	// priority to determine our current url.
	// BLTI-273
	public static String getOurServerUrl() {
		String ourUrl = ServerConfigurationService.getString("sakai.lti.serverUrl");
		if (ourUrl == null || ourUrl.equals(""))
			ourUrl = ServerConfigurationService.getString("serverUrl");
		if (ourUrl == null || ourUrl.equals(""))
			ourUrl = ServerConfigurationService.getServerUrl();
		if (ourUrl == null || ourUrl.equals(""))
			ourUrl = "http://127.0.0.1:8080";

		if ( ourUrl.endsWith("/")  && ourUrl.length() > 2 ) 
			ourUrl = ourUrl.substring(0,ourUrl.length()-1);

		return ourUrl;
	}

	public static String getOurServletPath(HttpServletRequest request)
	{
		String URLstr = request.getRequestURL().toString();
		String retval = URLstr.replaceFirst("^https??://[^/]*",getOurServerUrl());
		return retval;
	}

	public static String toNull(String str)
	{
		if ( str == null ) return null;
		if ( str.trim().length() < 1 ) return null;
		return str;
	}

	// Pull in a few things to avoid circular dependency
	public static int getInt(Object o)
	{
		if ( o instanceof String ) {
			try {
				return (new Integer((String) o)).intValue();
			} catch (Exception e) {
				return -1;
			}
		}
		if ( o instanceof Number ) return ( (Number) o).intValue();
		return -1;
	}

    public static String[] positional = { "field", "type" };
    public static Properties parseFormString(String str) {
        Properties op = new Properties();
        String[] pairs = str.split(":");
        int i = 0;
        for (String s : pairs) {
            String[] kv = s.split("=");
            if (kv.length == 2) {
                op.setProperty(kv[0], kv[1]);
            } else if (kv.length == 1 && i < positional.length) {
                op.setProperty(positional[i++], kv[0]);
            } else {
                // TODO : Log something here
            }
        }
        return op;
    }

    public static Long getLongKey(Object key) {
        return getLong(key);
    }

    public static Long getLong(Object key) {
        Long retval = getLongNull(key);
        if (retval != null)
            return retval;
        return new Long(-1);
    }

    public static Long getLongNull(Object key) {
        if (key == null)
            return null;
        if (key instanceof Number)
            return new Long(((Number) key).longValue());
        if (key instanceof String) {
            try {
                return new Long((String) key);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Setup a security advisor.
     */
    public static void pushAdvisor() {
        // setup a security advisor
        SecurityService.pushAdvisor(new SecurityAdvisor() {
                public SecurityAdvice isAllowed(String userId, String function,
                    String reference) {
                return SecurityAdvice.ALLOWED;
                }
                });
    }

    /**
     * Remove our security advisor.
     */
    public static void popAdvisor() {
        SecurityService.popAdvisor();
    }


}
