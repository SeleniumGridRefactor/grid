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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.ByteStreams;

import org.openqa.grid.common.SeleniumProtocol;
import org.openqa.grid.internal.ExternalSessionKey;
import org.openqa.grid.internal.HubRegistryInterface;
import org.openqa.grid.internal.TestSession;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

/**
 * wrapper around a selenium http request that helps accessing the internal 
 * details that are selenium related ( type of protocol, new session request
 * etc ) Also allows to change the content of the request, or read it 
 * on the hub.
 * 
 */
public abstract class SeleniumBasedRequest extends HttpServletRequestWrapper {

  private byte[] body;
  private final HubRegistryInterface registry;
  private final RequestType type;
  private final String encoding = "UTF-8";
  private final Map<String, Object> desiredCapability;
  private final long timestamp = System.currentTimeMillis();


  public static SeleniumBasedRequest createFromRequest(HttpServletRequest request, HubRegistryInterface registry) {
    if (SeleniumBasedRequest.getRequestProtocol(request) == SeleniumProtocol.Selenium) {
      return new LegacySeleniumRequest(request, registry);
    } else {
      return new WebDriverRequest(request, registry);
    }
  }


  /**
   * check the request and finds out if that's a selenium legacy protocol( RC ) or a WebDriver one.
   * @param request
   * @return Either SeleniumProtocol.Selenium or SeleniumProtocol.WebDriver.
   */
  public static SeleniumProtocol getRequestProtocol(HttpServletRequest request) {
    if ("/selenium-server/driver".equals(request.getServletPath())) {
      return SeleniumProtocol.Selenium;
    } else {
      return SeleniumProtocol.WebDriver;
    }
  }

  @VisibleForTesting
  public SeleniumBasedRequest(HttpServletRequest request, HubRegistryInterface registry, RequestType type,
      Map<String, Object> desiredCapability) {
    super(request);
    this.registry = registry;
    this.type = type;
    this.desiredCapability = desiredCapability;
  }

  public SeleniumBasedRequest(HttpServletRequest httpServletRequest, HubRegistryInterface registry) {
    super(httpServletRequest);
    try {
      InputStream is = super.getInputStream();
      setBody(ByteStreams.toByteArray(is));
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    this.registry = registry;
    type = extractRequestType();

    if (type == RequestType.START_SESSION) {
      desiredCapability = extractDesiredCapability();
    } else {
      desiredCapability = null;
    }
  }

  public HubRegistryInterface getRegistry() {
    return registry;
  }

  /**
   * @return the type of the request.
   */
  public abstract RequestType extractRequestType();

  /**
   * Extract the session from the request. This only works for a request that has a session already
   * assigned. It shouldn't be called for a new session request.
   *
   * @return the external session id sent by the remote. Null is the session cannot be found.
   */
  public abstract ExternalSessionKey extractSession();

  /**
   * Parse the request to extract the desiredCapabilities. For non web driver protocol ( selenium1 )
   * some mapping will be necessary
   *
   * @return the desired capabilities requested by the client.
   */
  public abstract Map<String, Object> extractDesiredCapability();


  // TODO freynaud remove the TestSession parameter.The listener can modify the 
  // original request instead.
  public abstract String getNewSessionRequestedCapability(TestSession session);



  public RequestType getRequestType() {
    return type;
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    return new ServletInputStreamImpl(new ByteArrayInputStream(body));
  }

  @Override
  public BufferedReader getReader() throws IOException {
    return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
  }
  
  @Override
  public int getContentLength() {
    if (body == null){
      return 0;  
    }else {
      return body.length;
    }
    
  }

  public String getBody() {
    try {
      Charset charset = Charset.forName(encoding);
      CharsetDecoder decoder = charset.newDecoder();
      CharBuffer cbuf = decoder.decode(ByteBuffer.wrap(body));
      return cbuf.toString();
    } catch (CharacterCodingException e) {
      e.printStackTrace();
    }
    return null;
  }

  public void setBody(String content) {
    setBody(content.getBytes());
  }

  public void setBody(byte[] content) {
    body = content;
    setAttribute("Content-Length", content.length);
  }

  public long getCreationTime(){
    return timestamp;
  }


  public String toString() {
    SimpleDateFormat format = new SimpleDateFormat("d MMM yyyy HH:mm:ss");
    StringBuilder builder = new StringBuilder();
    builder.append("["+format.format(new Date(timestamp))+"] ");
    builder.append(getMethod().toUpperCase() +" "+getPathInfo()+"   ");
    if (getBody() != null && !getBody().isEmpty()) {
      builder.append(getBody());
    }
    return builder.toString();
  }

  public Map<String, Object> getDesiredCapabilities() {
    return desiredCapability;
  }

  private class ServletInputStreamImpl extends ServletInputStream {

    private InputStream is;

    public ServletInputStreamImpl(InputStream is) {
      this.is = is;
    }

    public int read() throws IOException {
      return is.read();
    }

    public boolean markSupported() {
      return false;
    }

    public synchronized void mark(int i) {
      throw new RuntimeException("not implemented");
    }

    public synchronized void reset() throws IOException {
      throw new RuntimeException("not implemented");
    }
  }



}
