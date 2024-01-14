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

import feign.Feign;

/**
 * Allows application to customize the Feign builder.
 * FeignBuilderCustomizer 是一个接口，用于自定义 Feign 客户端构建器（Feign.Builder）的行为。它允许开发者在创建 Feign 客户端时应用一些自定义的配置或修改，以满足特定的需求。
 * 以下是关于 FeignBuilderCustomizer 的一些关键点：
 * 接口定义： FeignBuilderCustomizer 接口定义了一个方法 customize，该方法接受一个 Feign.Builder 实例作为参数，允许开发者对该构建器进行自定义配置。
 * 用途： 通过实现 FeignBuilderCustomizer 接口，开发者可以在 Feign 客户端的构建过程中介入，修改默认的配置，添加拦截器，或者进行其他定制化的操作。
 * 注入方式： 通常，FeignBuilderCustomizer 的实现类会以 Bean 的形式存在于 Spring 容器中。在 Feign 客户端构建时，Spring 会从容器中获取所有 FeignBuilderCustomizer 的实例，然后按照一定的顺序应用它们。
 * 与FeignContext的配合： 在上一个代码片段中，通过 Feign 上下文（FeignContext）获取 FeignBuilderCustomizer 的实例，并按照排序顺序应用到 Feign 构建器上。
 * 总体来说，FeignBuilderCustomizer 提供了一种扩展 Feign 客户端构建过程的机制，使得开发者可以根据自己的需求进行定制和配置，以满足特定场景下的要求
 *
 * @author Matt King
 */
@FunctionalInterface
public interface FeignBuilderCustomizer {

	void customize(Feign.Builder builder);

}
