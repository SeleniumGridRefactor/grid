/*
Copyright 2012 Selenium committers
Copyright 2012 Software Freedom Conservancy

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/


package org.openqa.grid.web.servlet.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.openqa.grid.common.exception.GridException;
import org.openqa.grid.internal.ExternalSessionKey;
import org.openqa.grid.internal.HubRegistryInterface;
import org.openqa.grid.internal.TestSession;
import org.openqa.selenium.remote.BeanToJsonConverter;
import org.openqa.selenium.remote.JsonToBeanConverter;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

public class WebDriverRequest extends SeleniumBasedRequest {

  public WebDriverRequest(HttpServletRequest httpServletRequest, HubRegistryInterface registry) {
    super(httpServletRequest, registry);
  }

  @Override
  public RequestType extractRequestType() {
    if ("/session".equals(getPathInfo())) {
      return RequestType.START_SESSION;
    } else if (getMethod().equalsIgnoreCase("DELETE")) {
      ExternalSessionKey externalKey = ExternalSessionKey.fromWebDriverRequest(getPathInfo());
      if (getPathInfo().endsWith("/session/" + externalKey.getKey())) {
        return RequestType.STOP_SESSION;
      }
    }
    return RequestType.REGULAR;
  }

  @Override
  public ExternalSessionKey extractSession() {
    if (getRequestType() == RequestType.START_SESSION) {
      throw new IllegalAccessError("Cannot call that method of a new session request.");
    }
    String path = getPathInfo();
    return ExternalSessionKey.fromWebDriverRequest(path);
  }

  @Override
  public Map<String, Object> extractDesiredCapability() {
    String json = getBody();
    try {
      JsonObject map = new JsonParser().parse(json).getAsJsonObject();
      JsonObject dc = map.get("desiredCapabilities").getAsJsonObject();
      return new JsonToBeanConverter().convert(Map.class, dc);

    } catch (JsonSyntaxException e) {
      throw new GridException("Cannot extract a capabilities from the request " + json);
    }
  }

  @Override
  public String getNewSessionRequestedCapability(TestSession session) {
    JsonObject c = new JsonObject();
    c.add("desiredCapabilities",
          new BeanToJsonConverter().convertObject(session.getRequestedCapabilities()));
    return new Gson().toJson(c);
  }
}
