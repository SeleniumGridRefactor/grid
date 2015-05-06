/*
Copyright 2007-2011 Selenium committers

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

package org.openqa.grid.internal.utils;

import org.openqa.selenium.Platform;
import org.openqa.selenium.remote.CapabilityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Default (naive) implementation of the capability matcher.
 * <p/>
 * The default capability matcher will look at all the key from the request do not start with _ and
 * will try to find a node that has at least those capabilities.
 */
public class DefaultCapabilityMatcher implements CapabilityMatcher {

  private static final Logger log = Logger.getLogger(DefaultCapabilityMatcher.class.getName());
  private static final String GRID_TOKEN = "_";

  // temporary fix to only check to most meaningful desiredCapability params
  private final List<String> toConsider = new ArrayList<String>();

  public DefaultCapabilityMatcher() {
    toConsider.add(CapabilityType.PLATFORM);
    toConsider.add(CapabilityType.BROWSER_NAME);
    toConsider.add(CapabilityType.VERSION);
    toConsider.add("applicationName");

  }

  public boolean matches(Map<String, Object> nodeCapability, Map<String, Object> requestedCapability) {
    if (nodeCapability == null || requestedCapability == null) {
      return false;
    }
    for (String key : requestedCapability.keySet()) {
      // ignore capabilities that are targeted at grid internal for the
      // matching
      // TODO freynaud only consider version, browser and OS for now
      if (!key.startsWith(GRID_TOKEN) && toConsider.contains(key)) {
        if (requestedCapability.get(key) != null) {
          String value = requestedCapability.get(key).toString();
          if (!("ANY".equalsIgnoreCase(value) || "".equals(value) || "*".equals(value))) {
            Platform requested = extractPlatform(requestedCapability.get(key));
            // special case for platform
            if (requested != null) {
              Platform node = extractPlatform(nodeCapability.get(key));
              if (node == null) {
                return false;
              }
              if (!node.is(requested)) {
                return false;
              }
            } else {
              if (!requestedCapability.get(key).equals(nodeCapability.get(key))) {
                return false;
              }
            }
          } else {
            // null value matches anything.
          }
        }
      }
    }
    return true;
  }

  Platform extractPlatform(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof Platform) {
      return (Platform) o;
    } else if (o instanceof String) {
      String name = o.toString();
      try {
        return Platform.valueOf(name);
      } catch (IllegalArgumentException e) {
        // no exact match, continue to look for a partial match
      }
      for (Platform os : Platform.values()) {
        for (String matcher : os.getPartOfOsName()) {
          if ("".equals(matcher))
            continue;
          if (name.equalsIgnoreCase(matcher)) {
            return os;
          }
        }
      }
      return null;
    } else {
      return null;
    }
  }
}
