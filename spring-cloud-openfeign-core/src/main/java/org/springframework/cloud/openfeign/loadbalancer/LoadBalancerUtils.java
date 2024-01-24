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

package org.springframework.cloud.openfeign.loadbalancer;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

import feign.Client;
import feign.Request;
import feign.Response;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.CompletionContext;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycle;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.ResponseData;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * @author Olga Maciaszek-Sharma
 *
 * A utility class for handling {@link LoadBalancerLifecycle} calls.
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
final class LoadBalancerUtils {

	private LoadBalancerUtils() {
		throw new IllegalStateException("Can't instantiate a utility class");
	}

	static Response executeWithLoadBalancerLifecycleProcessing(Client feignClient, Request.Options options,
			Request feignRequest, org.springframework.cloud.client.loadbalancer.Request lbRequest,
			org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse,
			Set<LoadBalancerLifecycle> supportedLifecycleProcessors, boolean loadBalanced, boolean useRawStatusCodes)
			throws IOException {
		supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStartRequest(lbRequest, lbResponse));
		try {
			Response response = feignClient.execute(feignRequest, options);
			if (loadBalanced) {
				supportedLifecycleProcessors.forEach(
						lifecycle -> lifecycle.onComplete(new CompletionContext<>(CompletionContext.Status.SUCCESS,
								lbRequest, lbResponse, buildResponseData(response, useRawStatusCodes))));
			}
			return response;
		}
		catch (Exception exception) {
			if (loadBalanced) {
				supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onComplete(
						new CompletionContext<>(CompletionContext.Status.FAILED, exception, lbRequest, lbResponse)));
			}
			throw exception;
		}
	}

	static ResponseData buildResponseData(Response response, boolean useRawStatusCodes) {
		HttpHeaders responseHeaders = new HttpHeaders();
		response.headers().forEach((key, value) -> responseHeaders.put(key, new ArrayList<>(value)));
		if (useRawStatusCodes) {
			return new ResponseData(responseHeaders, null, buildRequestData(response.request()), response.status());
		}
		return new ResponseData(HttpStatus.resolve(response.status()), responseHeaders, null,
				buildRequestData(response.request()));
	}

	static RequestData buildRequestData(Request request) {
		HttpHeaders requestHeaders = new HttpHeaders();
		request.headers().forEach((key, value) -> requestHeaders.put(key, new ArrayList<>(value)));
		return new RequestData(HttpMethod.resolve(request.httpMethod().name()), URI.create(request.url()),
				requestHeaders, null, new HashMap<>());
	}

	/**
	 *
	 * @param feignClient okhttp3.OkHttpClient
	 * @param options   超时时间相关配置：
	 * @param feignRequest： 如下
	 * POST http://10.22.78.252:8765/admin/insert_url?timestamp=1693566141&sign=fFIYKq/BlPzwcoOesqqR3pQDO9jhhtB5vPL%20GwYTbyk%3D&type=0&expire_ts=1790000000&complete_url=https%3A//blog.csdn.net/weixin_43582611/article/details/98889030 HTTP/1.1
	 * Env: dev
	 * Host-Address: 10.2.40.18
	 * Ih-Origin: ihuman-admin-service
	 *
	 * Binary data
	 *
	 * @param lbRequest
	 * [DefaultRequest@1859f533 context = [RequestDataContext@349a6ba5 clientRequest = [RequestData@49956653 httpMethod = POST,
	 * url = http://ihuman-turl-service/admin/insert_url?timestamp=1693566141&sign=fFIYKq/BlPzwcoOesqqR3pQDO9jhhtB5vPL%20GwYTbyk%3D&type=0&expire_ts=1790000000&complete_url=https%3A//blog.csdn.net/weixin_43582611/article/details/98889030,
	 * headers = map['Env' -> list['dev'], 'Host-Address' -> list['10.2.40.18'], 'Ih-Origin' -> list['ihuman-admin-service']], cookies = [null]]]]
	 * @param lbResponse: [DefaultResponse@3666744a serviceInstance = com.alibaba.cloud.nacos.NacosServiceInstance@e48a7d0f]
	 * @param supportedLifecycleProcessors
	 * @param useRawStatusCodes: false
	 * @return
	 * @throws IOException
	 */
	static Response executeWithLoadBalancerLifecycleProcessing(Client feignClient, Request.Options options,
			Request feignRequest, org.springframework.cloud.client.loadbalancer.Request lbRequest,
			org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse,
			Set<LoadBalancerLifecycle> supportedLifecycleProcessors, boolean useRawStatusCodes) throws IOException {
		return executeWithLoadBalancerLifecycleProcessing(feignClient, options, feignRequest, lbRequest, lbResponse,
				supportedLifecycleProcessors, true, useRawStatusCodes);
	}

}
