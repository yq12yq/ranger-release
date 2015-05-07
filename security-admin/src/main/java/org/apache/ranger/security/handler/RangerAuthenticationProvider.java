/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.security.handler;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import javax.security.auth.login.Configuration;
import org.apache.log4j.Logger;
import org.apache.ranger.authentication.unix.jaas.RoleUserAuthorityGranter;
import org.apache.ranger.common.PropertiesUtil;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.jaas.DefaultJaasAuthenticationProvider;
import org.springframework.security.authentication.jaas.memory.InMemoryConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.authentication.LdapAuthenticator;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;



public class RangerAuthenticationProvider implements AuthenticationProvider {
	private static Logger logger = Logger.getLogger(RangerAuthenticationProvider.class);
	private String rangerAuthenticationMethod;

	private LdapAuthenticator authenticator;

	public RangerAuthenticationProvider() {

	}

	@Override
	public Authentication authenticate(Authentication authentication)
			throws AuthenticationException {
		if (authentication != null && rangerAuthenticationMethod!=null) {
			if (rangerAuthenticationMethod.equalsIgnoreCase("LDAP")) {
				authentication=getLdapAuthentication(authentication);
				if(authentication!=null && authentication.isAuthenticated()){
					return authentication;
				}else{
					authentication=getLdapBindAuthentication(authentication);
					if(authentication!=null && authentication.isAuthenticated()){
						return authentication;
					}
				}
			}
			if (rangerAuthenticationMethod.equalsIgnoreCase("ACTIVE_DIRECTORY")) {
				authentication=getADBindAuthentication(authentication);
				if(authentication!=null && authentication.isAuthenticated()){
					return authentication;
				}else{
					authentication=getADAuthentication(authentication);
					if(authentication!=null && authentication.isAuthenticated()){
						return authentication;
					}
				}
			}
			if (rangerAuthenticationMethod.equalsIgnoreCase("UNIX")) {
				return getUnixAuthentication(authentication);
			}
			return null;
		}
		return null;
	}

	private Authentication getLdapAuthentication(Authentication authentication) {

		try {
			// getting ldap settings
			String rangerLdapURL = PropertiesUtil.getProperty(
					"ranger.ldap.url", "");
			String rangerLdapUserDNPattern = PropertiesUtil.getProperty(
					"ranger.ldap.user.dnpattern", "");
			String rangerLdapGroupSearchBase = PropertiesUtil.getProperty(
					"ranger.ldap.group.searchbase", "");
			String rangerLdapGroupSearchFilter = PropertiesUtil.getProperty(
					"ranger.ldap.group.searchfilter", "");
			String rangerLdapGroupRoleAttribute = PropertiesUtil.getProperty(
					"ranger.ldap.group.roleattribute", "");
			String rangerLdapDefaultRole = PropertiesUtil.getProperty(
					"ranger.ldap.default.role", "ROLE_USER");

			// taking the user-name and password from the authentication
			// object.
			String userName = authentication.getName();
			String userPassword = "";
			if (authentication.getCredentials() != null) {
				userPassword = authentication.getCredentials().toString();
			}

			// populating LDAP context source with LDAP URL and user-DN-pattern
			LdapContextSource ldapContextSource = new DefaultSpringSecurityContextSource(
					rangerLdapURL);

			ldapContextSource.setCacheEnvironmentProperties(false);
			ldapContextSource.setAnonymousReadOnly(true);

			// Creating LDAP authorities populator using Ldap context source and
			// Ldap group search base.
			// populating LDAP authorities populator with group search
			// base,group role attribute, group search filter.
			DefaultLdapAuthoritiesPopulator defaultLdapAuthoritiesPopulator = new DefaultLdapAuthoritiesPopulator(
					ldapContextSource, rangerLdapGroupSearchBase);
			defaultLdapAuthoritiesPopulator
					.setGroupRoleAttribute(rangerLdapGroupRoleAttribute);
			defaultLdapAuthoritiesPopulator
					.setGroupSearchFilter(rangerLdapGroupSearchFilter);
			defaultLdapAuthoritiesPopulator
					.setIgnorePartialResultException(true);

			// Creating BindAuthenticator using Ldap Context Source.
			BindAuthenticator bindAuthenticator = new BindAuthenticator(
					ldapContextSource);
			String[] userDnPatterns = new String[] { rangerLdapUserDNPattern };
			bindAuthenticator.setUserDnPatterns(userDnPatterns);

			// Creating Ldap authentication provider using BindAuthenticator and
			// Ldap authentication populator
			LdapAuthenticationProvider ldapAuthenticationProvider = new LdapAuthenticationProvider(
					bindAuthenticator, defaultLdapAuthoritiesPopulator);

			// getting user authenticated
			if (userName != null && userPassword != null
					&& !userName.trim().isEmpty()
					&& !userPassword.trim().isEmpty()) {
				final List<GrantedAuthority> grantedAuths = new ArrayList<>();
				grantedAuths.add(new SimpleGrantedAuthority(
						rangerLdapDefaultRole));

				final UserDetails principal = new User(userName, userPassword,
						grantedAuths);

				final Authentication finalAuthentication = new UsernamePasswordAuthenticationToken(
						principal, userPassword, grantedAuths);

				authentication = ldapAuthenticationProvider
						.authenticate(finalAuthentication);
				return authentication;
			} else {
				return null;
			}
		} catch (Exception e) {
			logger.error("LDAP Authentication Failed:"+e.getMessage());
		}
		return null;
	}

	public Authentication getADAuthentication(Authentication authentication) {
		try{
			String rangerADURL = PropertiesUtil.getProperty("ranger.ldap.ad.url",
					"");
			String rangerADDomain = PropertiesUtil.getProperty(
					"ranger.ldap.ad.domain", "");
			String rangerLdapDefaultRole = PropertiesUtil.getProperty(
					"ranger.ldap.default.role", "ROLE_USER");

			ActiveDirectoryLdapAuthenticationProvider adAuthenticationProvider = new ActiveDirectoryLdapAuthenticationProvider(
					rangerADDomain, rangerADURL);
			adAuthenticationProvider.setConvertSubErrorCodesToExceptions(true);
			adAuthenticationProvider.setUseAuthenticationRequestCredentials(true);

			// Grab the user-name and password out of the authentication object.
			String userName = authentication.getName();
			String userPassword = "";
			if (authentication.getCredentials() != null) {
				userPassword = authentication.getCredentials().toString();
			}

			// getting user authenticated
			if (userName != null && userPassword != null
					&& !userName.trim().isEmpty() && !userPassword.trim().isEmpty()) {
				final List<GrantedAuthority> grantedAuths = new ArrayList<>();
				grantedAuths.add(new SimpleGrantedAuthority(rangerLdapDefaultRole));
				final UserDetails principal = new User(userName, userPassword,
						grantedAuths);
				final Authentication finalAuthentication = new UsernamePasswordAuthenticationToken(
						principal, userPassword, grantedAuths);
				authentication = adAuthenticationProvider
						.authenticate(finalAuthentication);
				return authentication;
			} else {
				return null;
			}
		}catch (Exception e) {
			logger.error("AD Authentication Failed:"+e.getMessage());
		}
		return authentication;
	}

	public Authentication getUnixAuthentication(Authentication authentication) {

		try {
			String rangerLdapDefaultRole = PropertiesUtil.getProperty(
					"ranger.ldap.default.role", "ROLE_USER");
			DefaultJaasAuthenticationProvider jaasAuthenticationProvider = new DefaultJaasAuthenticationProvider();
			String loginModuleName = "org.apache.ranger.authentication.unix.jaas.RemoteUnixLoginModule";
			LoginModuleControlFlag controlFlag = LoginModuleControlFlag.REQUIRED;
			Map<String, String> options = (Map<String, String>) new HashMap<String, String>();
			options.put("configFile", "ranger-admin-site.xml");
			AppConfigurationEntry appConfigurationEntry = new AppConfigurationEntry(
					loginModuleName, controlFlag, options);
			AppConfigurationEntry[] appConfigurationEntries = new AppConfigurationEntry[] { appConfigurationEntry };
			Map<String, AppConfigurationEntry[]> appConfigurationEntriesOptions = (Map<String, AppConfigurationEntry[]>) new HashMap<String, AppConfigurationEntry[]>();
			appConfigurationEntriesOptions.put("SPRINGSECURITY",
					appConfigurationEntries);
			Configuration configuration = new InMemoryConfiguration(
					appConfigurationEntriesOptions);

			jaasAuthenticationProvider.setConfiguration(configuration);

			RoleUserAuthorityGranter authorityGranter = new RoleUserAuthorityGranter();

			authorityGranter.grant((Principal) authentication.getPrincipal());

			RoleUserAuthorityGranter[] authorityGranters = new RoleUserAuthorityGranter[] { authorityGranter };

			jaasAuthenticationProvider.setAuthorityGranters(authorityGranters);

			String userName = authentication.getName();
			String userPassword = "";
			if (authentication.getCredentials() != null) {
				userPassword = authentication.getCredentials().toString();
			}

			// getting user authenticated
			if (userName != null && userPassword != null
					&& !userName.trim().isEmpty()
					&& !userPassword.trim().isEmpty()) {
				final List<GrantedAuthority> grantedAuths = new ArrayList<>();
				grantedAuths.add(new SimpleGrantedAuthority(
						rangerLdapDefaultRole));
				final UserDetails principal = new User(userName, userPassword,
						grantedAuths);
				final Authentication finalAuthentication = new UsernamePasswordAuthenticationToken(
						principal, userPassword, grantedAuths);
				authentication = jaasAuthenticationProvider
						.authenticate(finalAuthentication);
				return authentication;
			} else {
				return null;
			}
		} catch (Exception e) {
			logger.error("Unix Authentication Failed:"+e.getMessage());
		}

		return authentication;
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return authentication.equals(UsernamePasswordAuthenticationToken.class);
	}

	public String getRangerAuthenticationMethod() {
		return rangerAuthenticationMethod;
	}

	public void setRangerAuthenticationMethod(String rangerAuthenticationMethod) {
		this.rangerAuthenticationMethod = rangerAuthenticationMethod;
	}

	public LdapAuthenticator getAuthenticator() {
		return authenticator;
	}

	public void setAuthenticator(LdapAuthenticator authenticator) {
		this.authenticator = authenticator;
	}

	private Authentication getADBindAuthentication(Authentication authentication) {
		try {
			String rangerADURL = PropertiesUtil.getProperty("ranger.ldap.ad.url", "");
			String rangerLdapADBase = PropertiesUtil.getProperty("ranger.ldap.ad.base.dn", "");
			String rangerADBindDN = PropertiesUtil.getProperty("ranger.ldap.ad.bind.dn", "");
			String rangerADBindPassword = PropertiesUtil.getProperty("ranger.ldap.ad.bind.password", "");
			String rangerLdapDefaultRole = PropertiesUtil.getProperty("ranger.ldap.default.role", "ROLE_USER");

			String userName = authentication.getName();
			String userPassword = "";
			if (authentication.getCredentials() != null) {
				userPassword = authentication.getCredentials().toString();
			}

			LdapContextSource ldapContextSource = new DefaultSpringSecurityContextSource(rangerADURL);
			ldapContextSource.setUserDn(rangerADBindDN);
			ldapContextSource.setPassword(rangerADBindPassword);
			ldapContextSource.setReferral("follow");
			ldapContextSource.setCacheEnvironmentProperties(true);
			ldapContextSource.setAnonymousReadOnly(false);
			ldapContextSource.setPooled(true);
			ldapContextSource.afterPropertiesSet();

			String searchFilter="(sAMAccountName={0})";
			FilterBasedLdapUserSearch userSearch=new FilterBasedLdapUserSearch(rangerLdapADBase, searchFilter,ldapContextSource);
			userSearch.setSearchSubtree(true);

			BindAuthenticator bindAuthenticator = new BindAuthenticator(ldapContextSource);
			bindAuthenticator.setUserSearch(userSearch);
			bindAuthenticator.afterPropertiesSet();

			LdapAuthenticationProvider ldapAuthenticationProvider = new LdapAuthenticationProvider(bindAuthenticator);

			if (userName != null && userPassword != null && !userName.trim().isEmpty() && !userPassword.trim().isEmpty()) {
				final List<GrantedAuthority> grantedAuths = new ArrayList<>();
				grantedAuths.add(new SimpleGrantedAuthority(rangerLdapDefaultRole));
				final UserDetails principal = new User(userName, userPassword,grantedAuths);
				final Authentication finalAuthentication = new UsernamePasswordAuthenticationToken(principal, userPassword, grantedAuths);

				authentication = ldapAuthenticationProvider.authenticate(finalAuthentication);
				return authentication;
			} else {
				return null;
			}
		} catch (Exception e) {
			logger.error("AD Authentication Failed:"+e.getMessage());
		}
		return authentication;
	}

	private Authentication getLdapBindAuthentication(Authentication authentication) {
		try {
			String rangerLdapURL = PropertiesUtil.getProperty("ranger.ldap.url", "");
			String rangerLdapUserDNPattern = PropertiesUtil.getProperty("ranger.ldap.user.dnpattern", "");
			String rangerLdapGroupSearchBase = PropertiesUtil.getProperty("ranger.ldap.group.searchbase", "");
			String rangerLdapGroupSearchFilter = PropertiesUtil.getProperty("ranger.ldap.group.searchfilter", "");
			String rangerLdapGroupRoleAttribute = PropertiesUtil.getProperty("ranger.ldap.group.roleattribute", "");
			String rangerLdapDefaultRole = PropertiesUtil.getProperty("ranger.ldap.default.role", "ROLE_USER");
			String rangerLdapBase = PropertiesUtil.getProperty("ranger.ldap.base.dn", "");
			String rangerLdapBindDN = PropertiesUtil.getProperty("ranger.ldap.bind.dn", "");
			String rangerLdapBindPassword = PropertiesUtil.getProperty("ranger.ldap.bind.password", "");

			String userName = authentication.getName();
			String userPassword = "";
			if (authentication.getCredentials() != null) {
				userPassword = authentication.getCredentials().toString();
			}

			LdapContextSource ldapContextSource = new DefaultSpringSecurityContextSource(rangerLdapURL);
			ldapContextSource.setUserDn(rangerLdapBindDN);
			ldapContextSource.setPassword(rangerLdapBindPassword);
			ldapContextSource.setReferral("follow");
			ldapContextSource.setCacheEnvironmentProperties(false);
			ldapContextSource.setAnonymousReadOnly(true);
			ldapContextSource.setPooled(true);
			ldapContextSource.afterPropertiesSet();

			DefaultLdapAuthoritiesPopulator defaultLdapAuthoritiesPopulator = new DefaultLdapAuthoritiesPopulator(ldapContextSource, rangerLdapGroupSearchBase);
			defaultLdapAuthoritiesPopulator.setGroupRoleAttribute(rangerLdapGroupRoleAttribute);
			defaultLdapAuthoritiesPopulator.setGroupSearchFilter(rangerLdapGroupSearchFilter);
			defaultLdapAuthoritiesPopulator.setIgnorePartialResultException(true);

			String searchFilter="(uid={0})";
			FilterBasedLdapUserSearch userSearch=new FilterBasedLdapUserSearch(rangerLdapBase, searchFilter,ldapContextSource);
			userSearch.setSearchSubtree(true);

			BindAuthenticator bindAuthenticator = new BindAuthenticator(ldapContextSource);
			bindAuthenticator.setUserSearch(userSearch);
			String[] userDnPatterns = new String[] { rangerLdapUserDNPattern };
			bindAuthenticator.setUserDnPatterns(userDnPatterns);
			bindAuthenticator.afterPropertiesSet();

			LdapAuthenticationProvider ldapAuthenticationProvider = new LdapAuthenticationProvider(bindAuthenticator,defaultLdapAuthoritiesPopulator);

			if (userName != null && userPassword != null && !userName.trim().isEmpty()&& !userPassword.trim().isEmpty()) {
				final List<GrantedAuthority> grantedAuths = new ArrayList<>();
				grantedAuths.add(new SimpleGrantedAuthority(rangerLdapDefaultRole));
				final UserDetails principal = new User(userName, userPassword,grantedAuths);
				final Authentication finalAuthentication = new UsernamePasswordAuthenticationToken(principal, userPassword, grantedAuths);

				authentication = ldapAuthenticationProvider.authenticate(finalAuthentication);
				return authentication;
			} else {
				return null;
			}
		} catch (Exception e) {
			logger.error("LDAP Authentication Failed:"+e.getMessage());
		}
		return authentication;
	}
}
