/*
 * Copyright 2013-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.openfeign;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import feign.Capability;
import feign.Client;
import feign.Contract;
import feign.ExceptionPropagationPolicy;
import feign.Feign;
import feign.Logger;
import feign.QueryMapEncoder;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.Target.HardCodedTarget;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.cloud.openfeign.clientconfig.FeignClientConfigurer;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.loadbalancer.RetryableFeignBlockingLoadBalancerClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * @author Spencer Gibb
 * @author Venil Noronha
 * @author Eko Kurniawan Khannedy
 * @author Gregor Zurowski
 * @author Matt King
 * @author Olga Maciaszek-Sharma
 * @author Ilia Ilinykh
 * @author Marcin Grzejszczak
 * @author Jonatan Ivanov
 * @author Sam Kruglov
 * @author Jasbir Singh
 */
public class FeignClientFactoryBean
	implements FactoryBean<Object>, InitializingBean, ApplicationContextAware, BeanFactoryAware {

	/***********************************
	 * WARNING! Nothing in this class should be @Autowired. It causes NPEs because of some
	 * lifecycle race condition.
	 ***********************************/

	private static Log LOG = LogFactory.getLog(FeignClientFactoryBean.class);

	private Class<?> type; // interface
							// org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests$FooClient

	private String name; // foo

	private String url; // https://foo

	private String contextId; // foo

	private String path; // ""

	private boolean decode404; // fasle

	private boolean inheritParentContext = true; // true

	private ApplicationContext applicationContext;

	private BeanFactory beanFactory; // org.springframework.beans.factory.support.DefaultListableBeanFactory

	private Class<?> fallback = void.class;

	private Class<?> fallbackFactory = void.class;

	private int readTimeoutMillis = new Request.Options().readTimeoutMillis(); // 60000

	private int connectTimeoutMillis = new Request.Options().connectTimeoutMillis(); // 10000

	private boolean followRedirects = new Request.Options().isFollowRedirects();

	private boolean refreshableClient = false;

	private final List<FeignBuilderCustomizer> additionalCustomizers = new ArrayList<>();

	@Override
	public void afterPropertiesSet() {
		Assert.hasText(contextId, "Context id must be set");
		Assert.hasText(name, "Name must be set");
	}
	// 用于创建和配置 Feign 客户端的构建器（Feign.Builder）的方法
	protected Feign.Builder feign(FeignContext context) {
		// 从 Feign 上下文中获取 FeignLoggerFactory 实例，并创建日志记录器
		FeignLoggerFactory loggerFactory = get(context, FeignLoggerFactory.class);
		Logger logger = loggerFactory.create(type);

		// 从 Feign 上下文中获取 Feign.Builder 实例，并配置必需的值
		Feign.Builder builder = get(context, Feign.Builder.class)
			.logger(logger)  // 设置日志记录器
			.encoder(get(context, Encoder.class))  // 设置编码器
			.decoder(get(context, Decoder.class))  // 设置解码器
			.contract(get(context, Contract.class)); // 设置验证器，用于实现模板解析

		// 配置 Feign 客户端
		configureFeign(context, builder);

		// 应用自定义构建器
		applyBuildCustomizers(context, builder);

		return builder;
	}

	/**
	 * 简要解释：
	 *
	 * 使用 Feign 上下文（FeignContext）通过 getInstances 方法获取特定类型（FeignBuilderCustomizer）的实例集合。
	 * 如果存在这些实例，通过流处理按照排序顺序应用这些自定义定制器到 Feign 的构建器（Feign.Builder）上。
	 * 最后，将额外的自定义定制器（additionalCustomizers）应用到 Feign 构建器上。
	 * 总体来说，这段代码的目的是在 Feign 构建时，通过应用自定义定制器来修改或扩展 Feign 客户端的行为。这样的设计允许开发者在构建 Feign 客户端时灵活地定制和配置一些特定的行为。
	 * @param context
	 * @param builder
	 */
	private void applyBuildCustomizers(FeignContext context, Feign.Builder builder) {
		// 从 Feign 上下文中获取 FeignBuilderCustomizer 实例的 Map
		Map<String, FeignBuilderCustomizer> customizerMap = context.getInstances(contextId, FeignBuilderCustomizer.class);

		// 如果存在自定义定制器的实例集合
		if (customizerMap != null) {
			// 获取实例集合中的所有值（FeignBuilderCustomizer 实例），并按照排序顺序应用这些定制器
			customizerMap.values().stream().sorted(AnnotationAwareOrderComparator.INSTANCE)
				.forEach(feignBuilderCustomizer -> feignBuilderCustomizer.customize(builder));
		}

		// 应用额外的定制器（additionalCustomizers）
		additionalCustomizers.forEach(customizer -> customizer.customize(builder));
	}

	protected void configureFeign(FeignContext context, Feign.Builder builder) {
		// 从 BeanFactory 或者 ApplicationContext 获取 FeignClientProperties 实例
		FeignClientProperties properties = beanFactory != null ? beanFactory.getBean(FeignClientProperties.class)
			: applicationContext.getBean(FeignClientProperties.class);

		// 获取 FeignClientConfigurer 实例
		FeignClientConfigurer feignClientConfigurer = getOptional(context, FeignClientConfigurer.class);

		// 设置是否继承父上下文的配置
		setInheritParentContext(feignClientConfigurer.inheritParentConfiguration());

		// 如果 properties 不为空并且允许继承父上下文的配置
		if (properties != null && inheritParentContext) {
			// 根据配置的默认行为，按照不同的顺序进行配置
			if (properties.isDefaultToProperties()) {
				configureUsingConfiguration(context, builder);  // 使用 Feign 配置
				configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()), builder);  // 使用默认配置
				configureUsingProperties(properties.getConfig().get(contextId), builder);  // 使用指定上下文的配置
			} else {
				configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()), builder);
				configureUsingProperties(properties.getConfig().get(contextId), builder);
				configureUsingConfiguration(context, builder);
			}
		} else {
			// 如果不允许继承父上下文的配置，则只使用 Feign 配置
			configureUsingConfiguration(context, builder);
		}
	}

	protected void configureUsingConfiguration(FeignContext context, Feign.Builder builder) {
		Logger.Level level = getInheritedAwareOptional(context, Logger.Level.class);
		if (level != null) {
			builder.logLevel(level);
		}
		Retryer retryer = getInheritedAwareOptional(context, Retryer.class);
		if (retryer != null) {
			builder.retryer(retryer);
		}
		ErrorDecoder errorDecoder = getInheritedAwareOptional(context, ErrorDecoder.class);
		if (errorDecoder != null) {
			builder.errorDecoder(errorDecoder);
		} else {
			FeignErrorDecoderFactory errorDecoderFactory = getOptional(context, FeignErrorDecoderFactory.class);
			if (errorDecoderFactory != null) {
				ErrorDecoder factoryErrorDecoder = errorDecoderFactory.create(type);
				builder.errorDecoder(factoryErrorDecoder);
			}
		}
		Request.Options options = getInheritedAwareOptional(context, Request.Options.class);
		if (options == null) {
			options = getOptionsByName(context, contextId);
		}

		if (options != null) {
			builder.options(options);
			readTimeoutMillis = options.readTimeoutMillis();
			connectTimeoutMillis = options.connectTimeoutMillis();
			followRedirects = options.isFollowRedirects();
		}
		Map<String, RequestInterceptor> requestInterceptors = getInheritedAwareInstances(context,
			RequestInterceptor.class);
		if (requestInterceptors != null) {
			List<RequestInterceptor> interceptors = new ArrayList<>(requestInterceptors.values());
			AnnotationAwareOrderComparator.sort(interceptors);
			builder.requestInterceptors(interceptors);
		}
		QueryMapEncoder queryMapEncoder = getInheritedAwareOptional(context, QueryMapEncoder.class);
		if (queryMapEncoder != null) {
			builder.queryMapEncoder(queryMapEncoder);
		}
		if (decode404) {
			builder.decode404();
		}
		ExceptionPropagationPolicy exceptionPropagationPolicy = getInheritedAwareOptional(context,
			ExceptionPropagationPolicy.class);
		if (exceptionPropagationPolicy != null) {
			builder.exceptionPropagationPolicy(exceptionPropagationPolicy);
		}

		Map<String, Capability> capabilities = getInheritedAwareInstances(context, Capability.class);
		if (capabilities != null) {
			capabilities.values().stream().sorted(AnnotationAwareOrderComparator.INSTANCE)
				.forEach(builder::addCapability);
		}
	}

	protected void configureUsingProperties(FeignClientProperties.FeignClientConfiguration config,
											Feign.Builder builder) {
		if (config == null) {
			return;
		}

		if (config.getLoggerLevel() != null) {
			builder.logLevel(config.getLoggerLevel());
		}

		if (!refreshableClient) {
			connectTimeoutMillis = config.getConnectTimeout() != null ? config.getConnectTimeout()
				: connectTimeoutMillis;
			readTimeoutMillis = config.getReadTimeout() != null ? config.getReadTimeout() : readTimeoutMillis;
			followRedirects = config.isFollowRedirects() != null ? config.isFollowRedirects() : followRedirects;

			builder.options(new Request.Options(connectTimeoutMillis, TimeUnit.MILLISECONDS, readTimeoutMillis,
				TimeUnit.MILLISECONDS, followRedirects));
		}

		if (config.getRetryer() != null) {
			Retryer retryer = getOrInstantiate(config.getRetryer());
			builder.retryer(retryer);
		}

		if (config.getErrorDecoder() != null) {
			ErrorDecoder errorDecoder = getOrInstantiate(config.getErrorDecoder());
			builder.errorDecoder(errorDecoder);
		}

		if (config.getRequestInterceptors() != null && !config.getRequestInterceptors().isEmpty()) {
			// this will add request interceptor to builder, not replace existing
			for (Class<RequestInterceptor> bean : config.getRequestInterceptors()) {
				RequestInterceptor interceptor = getOrInstantiate(bean);
				builder.requestInterceptor(interceptor);
			}
		}

		if (config.getDecode404() != null) {
			if (config.getDecode404()) {
				builder.decode404();
			}
		}

		if (Objects.nonNull(config.getEncoder())) {
			builder.encoder(getOrInstantiate(config.getEncoder()));
		}

		addDefaultRequestHeaders(config, builder);
		addDefaultQueryParams(config, builder);

		if (Objects.nonNull(config.getDecoder())) {
			builder.decoder(getOrInstantiate(config.getDecoder()));
		}

		if (Objects.nonNull(config.getContract())) {
			builder.contract(getOrInstantiate(config.getContract()));
		}

		if (Objects.nonNull(config.getExceptionPropagationPolicy())) {
			builder.exceptionPropagationPolicy(config.getExceptionPropagationPolicy());
		}

		if (config.getCapabilities() != null) {
			config.getCapabilities().stream().map(this::getOrInstantiate).forEach(builder::addCapability);
		}
	}

	private void addDefaultQueryParams(FeignClientProperties.FeignClientConfiguration config, Feign.Builder builder) {
		Map<String, Collection<String>> defaultQueryParameters = config.getDefaultQueryParameters();
		if (Objects.nonNull(defaultQueryParameters)) {
			builder.requestInterceptor(requestTemplate -> {
				Map<String, Collection<String>> queries = requestTemplate.queries();
				defaultQueryParameters.keySet().forEach(key -> {
					if (!queries.containsKey(key)) {
						requestTemplate.query(key, defaultQueryParameters.get(key));
					}
				});
			});
		}
	}

	private void addDefaultRequestHeaders(FeignClientProperties.FeignClientConfiguration config,
										  Feign.Builder builder) {
		Map<String, Collection<String>> defaultRequestHeaders = config.getDefaultRequestHeaders();
		if (Objects.nonNull(defaultRequestHeaders)) {
			builder.requestInterceptor(requestTemplate -> {
				Map<String, Collection<String>> headers = requestTemplate.headers();
				defaultRequestHeaders.keySet().forEach(key -> {
					if (!headers.containsKey(key)) {
						requestTemplate.header(key, defaultRequestHeaders.get(key));
					}
				});
			});
		}
	}

	private <T> T getOrInstantiate(Class<T> tClass) {
		try {
			return beanFactory != null ? beanFactory.getBean(tClass) : applicationContext.getBean(tClass);
		} catch (NoSuchBeanDefinitionException e) {
			return BeanUtils.instantiateClass(tClass);
		}
	}

	protected <T> T get(FeignContext context, Class<T> type) { // type=interface org.springframework.cloud.openfeign.Targeter

		T instance = context.getInstance(contextId, type); //  instance=org.springframework.cloud.openfeign.FeignHttpClientUrlTests$TestConfig$1@6a3a56de
		if (instance == null) { //
			throw new IllegalStateException("No bean found of type " + type + " for " + contextId);
		}
		return instance;
	}

	protected <T> T getOptional(FeignContext context, Class<T> type) {
		return context.getInstance(contextId, type);
	}

	protected <T> T getInheritedAwareOptional(FeignContext context, Class<T> type) {
		if (inheritParentContext) {
			return getOptional(context, type);
		} else {
			return context.getInstanceWithoutAncestors(contextId, type);
		}
	}

	protected <T> Map<String, T> getInheritedAwareInstances(FeignContext context, Class<T> type) {
		if (inheritParentContext) {
			return context.getInstances(contextId, type);
		} else {
			return context.getInstancesWithoutAncestors(contextId, type);
		}
	}

	protected <T> T loadBalance(Feign.Builder builder, FeignContext context, HardCodedTarget<T> target) { // target=HardCodedTarget(type=LoadbalancerTest, name=mockname, url=http://mockname)
		// Feign发送请求以及接受响应的http client，默认是Client.Default的实现，可以修改成OkHttp、HttpClient等。
		Client client = getOptional(context, Client.class); // org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient@6e489bb8
		if (client != null) {
			builder.client(client);
			Targeter targeter = get(context, Targeter.class);
			return targeter.target(this, builder, context, target);
		}

		throw new IllegalStateException(
			"No Feign Client for loadBalancing defined. Did you forget to include spring-cloud-starter-loadbalancer?");
	}

	/**
	 * Meant to get Options bean from context with bean name.
	 *
	 * @param context   context of Feign client
	 * @param contextId name of feign client
	 * @return returns Options found in context
	 */
	protected Request.Options getOptionsByName(FeignContext context, String contextId) {
		if (refreshableClient) {
			return context.getInstance(contextId, Request.Options.class.getCanonicalName() + "-" + contextId,
				Request.Options.class);
		}
		return null;
	}

	@Override
	public Object getObject() {
		return getTarget();
	}

	/**
	 * @param <T> the target type of the Feign client
	 * @return a {@link Feign} client created with the specified data and the context
	 * information
	 */
	<T> T getTarget() {
		// 实例化Feign上下文对象FeignContext
		/**
		 * FeignContext是全局唯一的上下文，它继承了NamedContextFactory，
		 * 它是用来来统一维护feign中各个feign客户端相互隔离的上下文，FeignContext注册到容器是在FeignAutoConfiguration上完成的
		 */
		FeignContext context = beanFactory != null ? beanFactory.getBean(FeignContext.class) // context=org.springframework.cloud.openfeign.FeignContext@20a4cba7
				: applicationContext.getBean(FeignContext.class);
		// 生成Builder对象，用来生成Feign
		Feign.Builder builder = feign(context);
		// 如果url为空，则走负载均衡，生成有负载均衡功能的代理类
		if (!StringUtils.hasText(url)) { // http://localhost:62122/path

			if (LOG.isInfoEnabled()) {
				LOG.info("For '" + name + "' URL not provided. Will try picking an instance via load-balancing.");
			}
			if (!name.startsWith("http")) {
				url = "http://" + name;
			} else {
				url = name;
			}
			url += cleanPath();
			// @FeignClient没有配置url属性，返回有负载均衡功能的代理对象
			return (T) loadBalance(builder, context, new HardCodedTarget<>(type, name, url));
		}
		if (StringUtils.hasText(url) && !url.startsWith("http")) {
			url = "http://" + url;
		}
		// 如果指定了url，则生成默认的代理类
		String url = this.url + cleanPath(); // http://localhost:62122/path
		Client client = getOptional(context, Client.class); // org.springframework.cloud.openfeign.loadbalancer.RetryableFeignBlockingLoadBalancerClient
		if (client != null) {
			if (client instanceof FeignBlockingLoadBalancerClient) {
				// not load balancing because we have a url,
				// but Spring Cloud LoadBalancer is on the classpath, so unwrap // 由于有指定 URL，不进行负载均衡，但 Spring Cloud LoadBalancer 在类路径中，因此进行解封装
				client = ((FeignBlockingLoadBalancerClient) client).getDelegate(); // client=feign.httpclient.ApacheHttpClient
			}
			if (client instanceof RetryableFeignBlockingLoadBalancerClient) {
				// not load balancing because we have a url,
				// but Spring Cloud LoadBalancer is on the classpath, so unwrap // 由于有指定 URL，不进行负载均衡，但 Spring Cloud LoadBalancer 在类路径中，因此进行解封装
				client = ((RetryableFeignBlockingLoadBalancerClient) client).getDelegate(); // ApacheHttpClient
			}
			builder.client(client);
		}
		Targeter targeter = get(context, Targeter.class); // org.springframework.cloud.openfeign.FeignHttpClientUrlTests$TestConfig$1@6a3a56de
		// 生成默认代理类
		return (T) targeter.target(this, builder, context, new HardCodedTarget<>(type, name, url));
	}

	private String cleanPath() {
		if (path == null) {
			return "";
		}
		String path = this.path.trim();
		if (StringUtils.hasLength(path)) {
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	@Override
	public Class<?> getObjectType() {
		return type;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	public Class<?> getType() {
		return type;
	}

	public void setType(Class<?> type) {
		this.type = type;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getContextId() {
		return contextId;
	}

	public void setContextId(String contextId) {
		this.contextId = contextId;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public boolean isDecode404() {
		return decode404;
	}

	public void setDecode404(boolean decode404) {
		this.decode404 = decode404;
	}

	public boolean isInheritParentContext() {
		return inheritParentContext;
	}

	public void setInheritParentContext(boolean inheritParentContext) {
		this.inheritParentContext = inheritParentContext;
	}

	public void addCustomizer(FeignBuilderCustomizer customizer) {
		additionalCustomizers.add(customizer);
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	@Override
	public void setApplicationContext(ApplicationContext context) throws BeansException {
		applicationContext = context;
		beanFactory = context;
	}

	public Class<?> getFallback() {
		return fallback;
	}

	public void setFallback(Class<?> fallback) {
		this.fallback = fallback;
	}

	public Class<?> getFallbackFactory() {
		return fallbackFactory;
	}

	public void setFallbackFactory(Class<?> fallbackFactory) {
		this.fallbackFactory = fallbackFactory;
	}

	public void setRefreshableClient(boolean refreshableClient) {
		this.refreshableClient = refreshableClient;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		FeignClientFactoryBean that = (FeignClientFactoryBean) o;
		return Objects.equals(applicationContext, that.applicationContext)
			&& Objects.equals(beanFactory, that.beanFactory) && decode404 == that.decode404
			&& inheritParentContext == that.inheritParentContext && Objects.equals(fallback, that.fallback)
			&& Objects.equals(fallbackFactory, that.fallbackFactory) && Objects.equals(name, that.name)
			&& Objects.equals(path, that.path) && Objects.equals(type, that.type) && Objects.equals(url, that.url)
			&& Objects.equals(connectTimeoutMillis, that.connectTimeoutMillis)
			&& Objects.equals(readTimeoutMillis, that.readTimeoutMillis)
			&& Objects.equals(followRedirects, that.followRedirects)
			&& Objects.equals(refreshableClient, that.refreshableClient);
	}

	@Override
	public int hashCode() {
		return Objects.hash(applicationContext, beanFactory, decode404, inheritParentContext, fallback, fallbackFactory,
			name, path, type, url, readTimeoutMillis, connectTimeoutMillis, followRedirects, refreshableClient);
	}

	@Override
	public String toString() {
		return new StringBuilder("FeignClientFactoryBean{").append("type=").append(type).append(", ").append("name='")
			.append(name).append("', ").append("url='").append(url).append("', ").append("path='").append(path)
			.append("', ").append("decode404=").append(decode404).append(", ").append("inheritParentContext=")
			.append(inheritParentContext).append(", ").append("applicationContext=").append(applicationContext)
			.append(", ").append("beanFactory=").append(beanFactory).append(", ").append("fallback=")
			.append(fallback).append(", ").append("fallbackFactory=").append(fallbackFactory).append("}")
			.append("connectTimeoutMillis=").append(connectTimeoutMillis).append("}").append("readTimeoutMillis=")
			.append(readTimeoutMillis).append("}").append("followRedirects=").append(followRedirects)
			.append("refreshableClient=").append(refreshableClient).append("}").toString();
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

}
