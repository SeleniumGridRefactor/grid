/*
Copyright 2011 Selenium committers
Copyright 2011-2015 Software Freedom Conservancy

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

package org.openqa.grid.selenium.proxy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.common.SeleniumProtocol;
import org.openqa.grid.common.exception.RemoteException;
import org.openqa.grid.common.exception.RemoteNotReachableException;
import org.openqa.grid.common.exception.RemoteUnregisterException;
import org.openqa.grid.internal.BaseRemoteProxy;
import org.openqa.grid.internal.HubRegistryInterface;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.listeners.CommandListener;
import org.openqa.grid.internal.listeners.SelfHealingProxy;
import org.openqa.grid.internal.listeners.TestSessionListener;
import org.openqa.grid.internal.listeners.TimeoutListener;
import org.openqa.grid.internal.utils.HtmlRenderer;
import org.openqa.grid.selenium.utils.WebProxyHtmlRenderer;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.remote.BrowserType;
import org.openqa.selenium.remote.CapabilityType;

import com.google.gson.JsonObject;

/**
 * Default remote proxy for selenium, handling both selenium1 and webdriver
 * requests.
 */
public class DefaultRemoteProxy extends BaseRemoteProxy implements
    TimeoutListener, SelfHealingProxy, CommandListener, TestSessionListener {

  private static final Logger log = Logger.getLogger(DefaultRemoteProxy.class
      .getName());

  public static final int DEFAULT_POLLING_INTERVAL = 10000;
  public static final int DEFAULT_UNREGISTER_DELAY = 60000;
  public static final int DEFAULT_DOWN_POLLING_LIMIT = 2;

  private volatile int pollingInterval = DEFAULT_POLLING_INTERVAL;
  private volatile int unregisterDelay = DEFAULT_UNREGISTER_DELAY;
  private volatile int downPollingLimit = DEFAULT_DOWN_POLLING_LIMIT;

  public DefaultRemoteProxy(RegistrationRequest request,
      HubRegistryInterface registry) {
    super(request, registry);

    pollingInterval = request.getConfigAsInt(RegistrationRequest.NODE_POLLING,
        DEFAULT_POLLING_INTERVAL);
    unregisterDelay = request.getConfigAsInt(
        RegistrationRequest.UNREGISTER_IF_STILL_DOWN_AFTER,
        DEFAULT_UNREGISTER_DELAY);
    downPollingLimit = request.getConfigAsInt(
        RegistrationRequest.DOWN_POLLING_LIMIT, DEFAULT_DOWN_POLLING_LIMIT);
  }

  @Override
  public void beforeRelease(TestSession session) {
    // release the resources remotely if the remote started a browser.
    if (session.getExternalKey() == null) {
      return;
    }
    boolean ok = session.sendDeleteSessionRequest();
    if (!ok) {
      log.warning("Error releasing the resources on timeout for session "
          + session);
    }
  }

  @Override
  public void afterCommand(TestSession session, HttpServletRequest request,
      HttpServletResponse response) {
    session.put("lastCommand",
        request.getMethod() + " - " + request.getPathInfo() + " executing ...");
  }

  @Override
  public void beforeCommand(TestSession session, HttpServletRequest request,
      HttpServletResponse response) {
    session.put("lastCommand",
        request.getMethod() + " - " + request.getPathInfo() + " executed.");
  }

  private final HtmlRenderer renderer = new WebProxyHtmlRenderer(this);

  @Override
  public HtmlRenderer getHtmlRender() {
    return renderer;
  }

  /*
   * Self Healing part. Polls the remote, and marks it down if it cannot be
   * reached twice in a row.
   */
  private volatile boolean down = false;
  private volatile boolean poll = true;

  // TODO freynaud
  private List<RemoteException> errors = new CopyOnWriteArrayList<RemoteException>();
  private Thread pollingThread = null;

  public boolean isAlive() {
    try {
      getStatus();
      return true;
    } catch (Exception e) {
      log.warning("Failed to check status of node: " + e.getMessage());
      return false;
    }
  }

  @Override
  public void startPolling() {
    pollingThread = new Thread(new Runnable() { // Thread safety reviewed
          int failedPollingTries = 0;
          long downSince = 0;

          @Override
          public void run() {
            while (poll) {
              try {
                Thread.sleep(pollingInterval);
                if (!isAlive()) {
                  if (!down) {
                    failedPollingTries++;
                    if (failedPollingTries >= downPollingLimit) {
                      downSince = System.currentTimeMillis();
                      addNewEvent(new RemoteNotReachableException(
                          "Marking the node as down. "
                              + "Cannot reach the node for "
                              + failedPollingTries + " tries."));
                    }
                  } else {
                    long downFor = System.currentTimeMillis() - downSince;
                    if (downFor > unregisterDelay) {
                      addNewEvent(new RemoteUnregisterException(
                          "Unregistering the node. It's been down for "
                              + downFor + " milliseconds."));
                    }
                  }
                } else {
                  down = false;
                  failedPollingTries = 0;
                  downSince = 0;
                }
              } catch (InterruptedException e) {
                return;
              }
            }
          }
        }, "RemoteProxy failure poller thread for " + getId());
    pollingThread.start();
  }

  @Override
  public void stopPolling() {
    poll = false;
    pollingThread.interrupt();
  }

  @Override
  public void addNewEvent(RemoteException event) {
    errors.add(event);
    onEvent(errors, event);

  }

  @Override
  public void onEvent(List<RemoteException> events, RemoteException lastInserted) {
    for (RemoteException e : events) {
      if (e instanceof RemoteNotReachableException) {
        log.warning(e.getMessage());
        down = true;
        this.errors.clear();
      }
      if (e instanceof RemoteUnregisterException) {
        log.warning(e.getMessage());
        HubRegistryInterface registry = this.getRegistry();
        registry.removeIfPresent(this);
      }
    }
  }

  /**
   * overwrites the session allocation to discard the proxy that are down.
   */
  @Override
  public TestSession getNewSession(Map<String, Object> requestedCapability) {
    if (down) {
      return null;
    }
    return super.getNewSession(requestedCapability);
  }

  public boolean isDown() {
    return down;
  }

  /**
   * The client shouldn't have to care where firefox is installed as long as the
   * correct version is launched, however with webdriver the binary location is
   * specified in the desiredCapability, making it the responsibility of the
   * person running the test.
   * 
   * With this implementation of beforeSession, that problem disappears . If the
   * webdriver slot is registered with a firefox using a custom binary location,
   * the hub will handle it.
   * 
   * <p>
   * For instance if a node registers:
   * {"browserName":"firefox","version":"7.0","firefox_binary":"/home/ff7"}
   * 
   * and later on a client requests {"browserName":"firefox","version":"7.0"} ,
   * the hub will automatically append the correct binary path to the
   * desiredCapability before it's forwarded to the server. That way the version
   * / install location mapping is done only once at the node level.
   */
  @Override
  public void beforeSession(TestSession session) {
    if (session.getSlot().getProtocol() == SeleniumProtocol.WebDriver) {
      Map<String, Object> cap = session.getRequestedCapabilities();

      if (BrowserType.FIREFOX.equals(cap.get(CapabilityType.BROWSER_NAME))) {
        if (session.getSlot().getCapabilities().get(FirefoxDriver.BINARY) != null
            && cap.get(FirefoxDriver.BINARY) == null) {
          session.getRequestedCapabilities().put(FirefoxDriver.BINARY,
              session.getSlot().getCapabilities().get(FirefoxDriver.BINARY));
        }
      }

      if (BrowserType.CHROME.equals(cap.get(CapabilityType.BROWSER_NAME))) {
        if (session.getSlot().getCapabilities().get("chrome_binary") != null) {
          JsonObject options = (JsonObject) cap.get(ChromeOptions.CAPABILITY);
          if (options == null) {
            options = new JsonObject();
          }
          options.addProperty("binary", (String) session.getSlot()
              .getCapabilities().get("chrome_binary"));
          cap.put(ChromeOptions.CAPABILITY, options);
        }
      }
    }
  }

  @Override
  public void afterSession(TestSession session) {
    // TODO Auto-generated method stub

  }

  @Override
  public void teardown() {
    super.teardown();
    stopPolling();
  }
}
