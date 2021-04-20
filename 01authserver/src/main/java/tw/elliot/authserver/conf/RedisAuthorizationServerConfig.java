package tw.elliot.authserver.conf;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.config.annotation.configurers.ClientDetailsServiceConfigurer;
import org.springframework.security.oauth2.config.annotation.web.configuration.AuthorizationServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableAuthorizationServer;
import org.springframework.security.oauth2.config.annotation.web.configurers.AuthorizationServerEndpointsConfigurer;
import org.springframework.security.oauth2.config.annotation.web.configurers.AuthorizationServerSecurityConfigurer;
import org.springframework.security.oauth2.provider.token.AccessTokenConverter;
import org.springframework.security.oauth2.provider.token.TokenStore;

@Profile("redis")
@Configuration
@EnableAuthorizationServer
public class RedisAuthorizationServerConfig extends AuthorizationServerConfigurerAdapter {
	private static final String CLIENT_ID = "app";
	private static final String CLIENT_SECRET = "{noop}654321";
	private static final String RESOURCE_ID = "RESTFul";
	@Autowired
	private AuthenticationManager authenticationManager;

	@Autowired
	private TokenStore tokenStore;

	/*
	@Autowired
	private AccessTokenConverter accessTokenConverter;
	 */

	@Override
	public void configure(ClientDetailsServiceConfigurer clients) throws Exception {
		clients.inMemory()
				.withClient(CLIENT_ID)
				.resourceIds(RESOURCE_ID)
				.authorizedGrantTypes("authorization_code", "password", "refresh_token")
				.scopes("read", "write", "trust")
				.authorities("oauth2")
				.secret(CLIENT_SECRET);
	}

	@Override
	public void configure(AuthorizationServerEndpointsConfigurer endpoints) throws Exception {

		endpoints
				//.accessTokenConverter(accessTokenConverter)
				.authenticationManager(authenticationManager)
				.tokenStore(tokenStore);
	}


	@Override
	public void configure(AuthorizationServerSecurityConfigurer security) throws Exception {
		security.allowFormAuthenticationForClients();
	}
}
