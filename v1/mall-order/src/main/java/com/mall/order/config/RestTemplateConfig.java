package com.mall.order.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置：把它交给 Spring 管理，业务代码里 @Autowired 注入即可用。
 * 必须设置超时：不设的话下游服务卡住时，调用线程会一直等，上游也被拖死。
 */
@Configuration
public class RestTemplateConfig {

	@Bean
	public RestTemplate restTemplate() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		// 连接超时：TCP 握手多久算失败
		factory.setConnectTimeout(Duration.ofSeconds(3));
		// 读取超时：连上之后等响应最多等多久
		factory.setReadTimeout(Duration.ofSeconds(3));
		return new RestTemplate(factory);
	}
}
