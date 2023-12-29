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

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import feign.Request;

import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * @author Spencer Gibb
 * @author Jakub Narloch
 * @author Venil Noronha
 * @author Gang Li
 * @author Michal Domagala
 * @author Marcin Grzejszczak
 * @author Olga Maciaszek-Sharma
 * @author Jasbir Singh
 */
class FeignClientsRegistrar implements ImportBeanDefinitionRegistrar, ResourceLoaderAware, EnvironmentAware {

	// patterned after Spring Integration IntegrationComponentScanRegistrar
	// and RibbonClientsConfigurationRegistgrar

	private ResourceLoader resourceLoader;

	private Environment environment;

	FeignClientsRegistrar() {
	}

	static void validateFallback(final Class clazz) {
		Assert.isTrue(!clazz.isInterface(), "Fallback class must implement the interface annotated by @FeignClient");
	}

	static void validateFallbackFactory(final Class clazz) {
		Assert.isTrue(!clazz.isInterface(), "Fallback factory must produce instances "
				+ "of fallback classes that implement the interface annotated by @FeignClient");
	}
	// 从传入的服务名称中提取出主机名（host）
	static String getName(String name) { // foo
		if (!StringUtils.hasText(name)) {
			return "";
		}

		String host = null;
		try {
			String url;
			if (!name.startsWith("http://") && !name.startsWith("https://")) {
				url = "http://" + name; // http://foo
			}
			else {
				url = name;
			}
			host = new URI(url).getHost();

		}
		catch (URISyntaxException e) {
		}
		Assert.state(host != null, "Service id not legal hostname (" + name + ")");
		return name;
	}

	static String getUrl(String url) {
		if (StringUtils.hasText(url) && !(url.startsWith("#{") && url.contains("}"))) {
			if (!url.contains("://")) {
				url = "http://" + url;
			}
			try {
				new URL(url);
			}
			catch (MalformedURLException e) {
				throw new IllegalArgumentException(url + " is malformed", e);
			}
		}
		return url;
	}

	static String getPath(String path) {
		if (StringUtils.hasText(path)) {
			path = path.trim();
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
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	@Override
	public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
		//注册全局配置，解析EnableFeignClients注解上配置的defaultConfiguration属性
		registerDefaultConfiguration(metadata, registry);
		//扫描指定的所有包名下的被@FeignClient注解注释的接口，将扫描出来的接口调用registerFeignClient方法注册到spring容器
		registerFeignClients(metadata, registry);
	}
	// metadata:org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests$TestConfiguration
	private void registerDefaultConfiguration(AnnotationMetadata metadata, BeanDefinitionRegistry registry) { // org.springframework.beans.factory.support.DefaultListableBeanFactory
		//解析EnableFeignClients属性
		Map<String, Object> defaultAttrs = metadata.getAnnotationAttributes(EnableFeignClients.class.getName(), true);

		if (defaultAttrs != null && defaultAttrs.containsKey("defaultConfiguration")) {
			String name;
			if (metadata.hasEnclosingClass()) {
				name = "default." + metadata.getEnclosingClassName(); // default.org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests
			}
			else {
				name = "default." + metadata.getClassName(); //
			}
			//注册客户端配置
			registerClientConfiguration(registry, name, defaultAttrs.get("defaultConfiguration"));
		}
	}

	public void registerFeignClients(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {

		LinkedHashSet<BeanDefinition> candidateComponents = new LinkedHashSet<>();
		// 扫描带有EnableFeignClients注解的类
		Map<String, Object> attrs = metadata.getAnnotationAttributes(EnableFeignClients.class.getName());
		// 获取@EnableFeignClients 中clients的值
		final Class<?>[] clients = attrs == null ? null : (Class<?>[]) attrs.get("clients");
		if (clients == null || clients.length == 0) {
			ClassPathScanningCandidateComponentProvider scanner = getScanner();
			scanner.setResourceLoader(this.resourceLoader);
			scanner.addIncludeFilter(new AnnotationTypeFilter(FeignClient.class));
			// 如果没有设置，则扫描的包路径为 @EnableFeignClients 注解所在的包
			Set<String> basePackages = getBasePackages(metadata);
			for (String basePackage : basePackages) {
				candidateComponents.addAll(scanner.findCandidateComponents(basePackage));
			}
		}
		else {
			for (Class<?> clazz : clients) {  //设置了, clients属性，则使用注解属性来进行扫描注册
				candidateComponents.add(new AnnotatedGenericBeanDefinition(clazz)); // clazz: interface org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests$FooClient
			}
		}
		//循环扫描注册
		for (BeanDefinition candidateComponent : candidateComponents) {
			if (candidateComponent instanceof AnnotatedBeanDefinition) {
				// verify annotated class is an interface
				AnnotatedBeanDefinition beanDefinition = (AnnotatedBeanDefinition) candidateComponent; // Generic bean: class [org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests$FooClient]; scope=; abstract=false; lazyInit=null; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null
				AnnotationMetadata annotationMetadata = beanDefinition.getMetadata(); // org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests$FooClient
				Assert.isTrue(annotationMetadata.isInterface(), "@FeignClient can only be specified on an interface");

				Map<String, Object> attributes = annotationMetadata
						.getAnnotationAttributes(FeignClient.class.getCanonicalName());

				String name = getClientName(attributes); // bar
				//注册被调用客户端配置
				registerClientConfiguration(registry, name, attributes.get("configuration"));
				registerFeignClient(registry, annotationMetadata, attributes); //注册 FeignClient
			}
		}
	}

	/**
	 * 创建一个BeanDefinitionBuilder。
	 * 创建一个工厂Bean，并把从@FeignClient注解中解析的属性设置到这个FactoryBean中
	 * 调用registerBeanDefinition注册到IOC容器中
	 *
	 * @param registry
	 * @param annotationMetadata
	 * @param attributes
	 */
	// 注册 FeignClient，组装BeanDefinition，实质是一个FeignClientFactoryBean，然后注册到Spring IOC容器
	private void registerFeignClient(BeanDefinitionRegistry registry, AnnotationMetadata annotationMetadata,
			Map<String, Object> attributes) { //DefaultListableBeanFactory、FooClient上的@FeignClient元数据，@FeignClient的所有属性[{configuration=[class org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests$FooConfiguration], contextId=, decode404=false, fallback=void, fallbackFactory=void, name=foo, path=, primary=true, qualifier=, qualifiers=[], url=https://foo, value=foo}]
		String className = annotationMetadata.getClassName(); // org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests$FooClient
		Class clazz = ClassUtils.resolveClassName(className, null); // interface org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests$FooClient
		ConfigurableBeanFactory beanFactory = registry instanceof ConfigurableBeanFactory // DefaultListableBeanFactory
				? (ConfigurableBeanFactory) registry : null;
		String contextId = getContextId(beanFactory, attributes); // foo
		String name = getName(attributes); // foo
		//构建一个FeignClient FactoryBean，这个是工厂Bean。
		FeignClientFactoryBean factoryBean = new FeignClientFactoryBean(); //
		factoryBean.setBeanFactory(beanFactory); //org.springframework.beans.factory.support.DefaultListableBeanFactory@352e787a: defining beans [org.springframework.boot.test.mock.mockito.MockitoPostProcessor$SpyPostProcessor,org.springframework.boot.test.mock.mockito.MockitoPostProcessor,org.springframework.context.annotation.internalConfigurationAnnotationProcessor,org.springframework.context.annotation.internalAutowiredAnnotationProcessor,org.springframework.context.annotation.internalCommonAnnotationProcessor,org.springframework.context.event.internalEventListenerProcessor,org.springframework.context.event.internalEventListenerFactory,feignClientDisabledFeaturesTests.TestConfiguration,org.springframework.boot.autoconfigure.internalCachingMetadataReaderFactory,org.springframework.boot.autoconfigure.AutoConfigurationPackages,org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor,org.springframework.boot.context.internalConfigurationPropertiesBinderFactory,org.springframework.boot.context.internalConfigurationPropertiesBinder,org.springframework.boot.context.properties.BoundConfigurationProperties,org.springframework.boot.context.properties.EnableConfigurationPropertiesRegistrar.methodValidationExcludeFilter,feign.client-org.springframework.cloud.openfeign.FeignClientProperties,default.org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests.FeignClientSpecification,foo.FeignClientSpecification]; root of factory hierarchy
		factoryBean.setName(name); // foo
		factoryBean.setContextId(contextId); // foo
		factoryBean.setType(clazz); // interface org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests$FooClient
		factoryBean.setRefreshableClient(isClientRefreshEnabled());
		//BeanDefinitionBuilder是用来构建BeanDefinition对象的建造器
		BeanDefinitionBuilder definition = BeanDefinitionBuilder.genericBeanDefinition(clazz, () -> {
			//把@FeignClient注解配置中的属性设置到FactoryBean中。
			factoryBean.setUrl(getUrl(beanFactory, attributes));
			factoryBean.setPath(getPath(beanFactory, attributes));
			factoryBean.setDecode404(Boolean.parseBoolean(String.valueOf(attributes.get("decode404"))));
			Object fallback = attributes.get("fallback");
			if (fallback != null) {
				factoryBean.setFallback(fallback instanceof Class ? (Class<?>) fallback
						: ClassUtils.resolveClassName(fallback.toString(), null));
			}
			Object fallbackFactory = attributes.get("fallbackFactory");
			if (fallbackFactory != null) {
				factoryBean.setFallbackFactory(fallbackFactory instanceof Class ? (Class<?>) fallbackFactory
						: ClassUtils.resolveClassName(fallbackFactory.toString(), null));
			}
			return factoryBean.getObject(); //factoryBean.getObject() ，基于工厂bean创造一个bean实例。
		});
		definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE); // 通过类型注入
		definition.setLazyInit(true); // 懒加载
		validate(attributes);
		//从BeanDefinitionBuilder中构建一个BeanDefinition，它用来描述一个bean的实例定义
		// Generic bean: class [org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests$FooClient]; scope=; abstract=false; lazyInit=true; autowireMode=2; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null
		AbstractBeanDefinition beanDefinition = definition.getBeanDefinition();
		beanDefinition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, className); // 设置指定属性值factoryBeanObjectType=org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests$FooClient
		beanDefinition.setAttribute("feignClientsRegistrarFactoryBean", factoryBean); // 同理：FeignClientFactoryBean{type=interface org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests$FooClient, name='foo', url='null', path='null', decode404=false, inheritParentContext=true, applicationContext=null, beanFactory=org.springframework.beans.factory.support.DefaultListableBeanFactory@352e787a: defining beans [org.springframework.boot.test.mock.mockito.MockitoPostProcessor$SpyPostProcessor,org.springframework.boot.test.mock.mockito.MockitoPostProcessor,org.springframework.context.annotation.internalConfigurationAnnotationProcessor,org.springframework.context.annotation.internalAutowiredAnnotationProcessor,org.springframework.context.annotation.internalCommonAnnotationProcessor,org.springframework.context.event.internalEventListenerProcessor,org.springframework.context.event.internalEventListenerFactory,feignClientDisabledFeaturesTests.TestConfiguration,org.springframework.boot.autoconfigure.internalCachingMetadataReaderFactory,org.springframework.boot.autoconfigure.AutoConfigurationPackages,org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor,org.springframework.boot.context.internalConfigurationPropertiesBinderFactory,org.springframework.boot.context.internalConfigurationPropertiesBinder,org.springframework.boot.context.properties.BoundConfigurationProperties,org.springframework.boot.context.properties.EnableConfigurationPropertiesRegistrar.methodValidationExcludeFilter,feign.client-org.springframework.cloud.openfeign.FeignClientProperties,default.org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests.FeignClientSpecification,foo.FeignClientSpecification]; root of factory hierarchy, fallback=void, fallbackFactory=void}connectTimeoutMillis=10000}readTimeoutMillis=60000}followRedirects=truerefreshableClient=false}

		// has a default, won't be null, 设置primary
		boolean primary = (Boolean) attributes.get("primary");

		beanDefinition.setPrimary(primary);

		String[] qualifiers = getQualifiers(attributes); // feignClient的指定名称
		if (ObjectUtils.isEmpty(qualifiers)) {
			qualifiers = new String[] { contextId + "FeignClient" }; // fooFeignClient
		}
		// 注册到IOC容器
		BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, className, qualifiers);
		BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);

		registerOptionsBeanDefinition(registry, contextId);
	}
	// {configuration=[class org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests$FooConfiguration], contextId=, decode404=false, fallback=void, fallbackFactory=void, name=foo, path=, primary=true, qualifier=, qualifiers=[], url=https://foo, value=foo}
	private void validate(Map<String, Object> attributes) {
		AnnotationAttributes annotation = AnnotationAttributes.fromMap(attributes);
		// This blows up if an aliased property is overspecified
		// FIXME annotation.getAliasedString("name", FeignClient.class, null);
		validateFallback(annotation.getClass("fallback"));
		validateFallbackFactory(annotation.getClass("fallbackFactory"));
	}

	/* for testing */ String getName(Map<String, Object> attributes) {
		return getName(null, attributes);
	}

	String getName(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) { //
		String name = (String) attributes.get("serviceId");
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("name");
		}
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("value");
		}
		name = resolve(beanFactory, name);
		return getName(name);
	}

	private String getContextId(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		String contextId = (String) attributes.get("contextId");
		if (!StringUtils.hasText(contextId)) {
			return getName(attributes);
		}

		contextId = resolve(beanFactory, contextId);
		return getName(contextId);
	}
	// 解析配置值的方法，具体来说，它用于解析字符串中的占位符和表达式
	private String resolve(ConfigurableBeanFactory beanFactory, String value) {
		if (StringUtils.hasText(value)) {
			if (beanFactory == null) { // 如果 beanFactory 为空，直接使用 environment 解析占位符
				return this.environment.resolvePlaceholders(value);
			}
			BeanExpressionResolver resolver = beanFactory.getBeanExpressionResolver(); // 获取 BeanExpressionResolver 和解析后的值
			String resolved = beanFactory.resolveEmbeddedValue(value);
			if (resolver == null) {  // 如果 resolver 为空，直接返回解析后的值
				return resolved;
			}
			Object evaluateValue = resolver.evaluate(resolved, new BeanExpressionContext(beanFactory, null)); // 使用 BeanExpressionResolver 解析表达式
			if (evaluateValue != null) { // 如果解析结果不为空，将其转换为字符串返回；否则返回 null
				return String.valueOf(evaluateValue);
			}
			return null;
		}
		return value;  // 如果传入的字符串为空，则直接返回原始值
	}

	private String getUrl(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		String url = resolve(beanFactory, (String) attributes.get("url"));
		return getUrl(url);
	}

	private String getPath(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		String path = resolve(beanFactory, (String) attributes.get("path"));
		return getPath(path);
	}

	protected ClassPathScanningCandidateComponentProvider getScanner() {
		return new ClassPathScanningCandidateComponentProvider(false, this.environment) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
				boolean isCandidate = false;
				if (beanDefinition.getMetadata().isIndependent()) {
					if (!beanDefinition.getMetadata().isAnnotation()) {
						isCandidate = true;
					}
				}
				return isCandidate;
			}
		};
	}
	// 如果没有设置，则扫描的包路径为 @EnableFeignClients 注解所在的包
	protected Set<String> getBasePackages(AnnotationMetadata importingClassMetadata) {
		Map<String, Object> attributes = importingClassMetadata
				.getAnnotationAttributes(EnableFeignClients.class.getCanonicalName());

		Set<String> basePackages = new HashSet<>();
		for (String pkg : (String[]) attributes.get("value")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (String pkg : (String[]) attributes.get("basePackages")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (Class<?> clazz : (Class[]) attributes.get("basePackageClasses")) {
			basePackages.add(ClassUtils.getPackageName(clazz));
		}

		if (basePackages.isEmpty()) {
			basePackages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
		}
		return basePackages;
	}

	private String getQualifier(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		String qualifier = (String) client.get("qualifier");
		if (StringUtils.hasText(qualifier)) {
			return qualifier;
		}
		return null;
	}
	// 从传入的客户端配置（client）中提取出限定符（qualifiers）
	private String[] getQualifiers(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		List<String> qualifierList = new ArrayList<>(Arrays.asList((String[]) client.get("qualifiers")));
		qualifierList.removeIf(qualifier -> !StringUtils.hasText(qualifier));
		if (qualifierList.isEmpty() && getQualifier(client) != null) { // 如果限定符列表为空，但存在单个限定符，使用该单个限定符(qualifier)
			qualifierList = Collections.singletonList(getQualifier(client));
		}
		return !qualifierList.isEmpty() ? qualifierList.toArray(new String[0]) : null;
	}
	// 根据@FeignClient注解的属性来获取客户端名称
	private String getClientName(Map<String, Object> client) {  // @FeignClient注解的属性
		if (client == null) {
			return null;
		}
		String value = (String) client.get("contextId");
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("value");
		}
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("name");
		}
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("serviceId");
		}
		if (StringUtils.hasText(value)) {
			return value;
		}

		throw new IllegalStateException(
				"Either 'name' or 'value' must be provided in @" + FeignClient.class.getSimpleName());
	}

	private void registerClientConfiguration(BeanDefinitionRegistry registry, Object name, Object configuration) {
		BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(FeignClientSpecification.class);
		builder.addConstructorArgValue(name);
		builder.addConstructorArgValue(configuration);
		registry.registerBeanDefinition(name + "." + FeignClientSpecification.class.getSimpleName(), //default.org.springframework.cloud.openfeign.FeignClientDisabledFeaturesTests.FeignClientSpecification、
				builder.getBeanDefinition());
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	/**
	 * This method is meant to create {@link Request.Options} beans definition with
	 * refreshScope.   用于配置 Feign 客户端选项的 bean 定义，以支持动态刷新
	 * @param registry spring bean definition registry
	 * @param contextId name of feign client
	 */
	private void registerOptionsBeanDefinition(BeanDefinitionRegistry registry, String contextId) {
		if (isClientRefreshEnabled()) {
			String beanName = Request.Options.class.getCanonicalName() + "-" + contextId;
			BeanDefinitionBuilder definitionBuilder = BeanDefinitionBuilder
					.genericBeanDefinition(OptionsFactoryBean.class);
			definitionBuilder.setScope("refresh");
			definitionBuilder.addPropertyValue("contextId", contextId);
			BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(definitionBuilder.getBeanDefinition(),
					beanName);
			definitionHolder = ScopedProxyUtils.createScopedProxy(definitionHolder, registry, true);
			BeanDefinitionReaderUtils.registerBeanDefinition(definitionHolder, registry);
		}
	}

	private boolean isClientRefreshEnabled() {
		return environment.getProperty("feign.client.refresh-enabled", Boolean.class, false);
	}

}
