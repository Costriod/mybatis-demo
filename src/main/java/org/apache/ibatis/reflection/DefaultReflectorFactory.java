/**
 *    Copyright 2009-2015 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * DefaultReflectorFactory有一个缓存ConcurrentMap<Class<?>, Reflector> reflectorMap用来存储类和反射器的映射关系
 */
public class DefaultReflectorFactory implements ReflectorFactory {
  private boolean classCacheEnabled = true;
  private final ConcurrentMap<Class<?>, Reflector> reflectorMap = new ConcurrentHashMap<Class<?>, Reflector>();

  public DefaultReflectorFactory() {
  }

  @Override
  public boolean isClassCacheEnabled() {
    return classCacheEnabled;
  }

  @Override
  public void setClassCacheEnabled(boolean classCacheEnabled) {
    this.classCacheEnabled = classCacheEnabled;
  }

  /**
   * 通过类寻找反射器Reflector，如果classCacheEnabled为true，先从reflectorMap查找Reflector，
   * 缓存reflectorMap不存在则生成一个新的Reflector对象，并把Reflector存入缓存reflectorMap
   * 如果classCacheEnabled为false，则每次都创建新的Reflector
   * classCacheEnabled默认是true，也就是默认开启Reflector缓存
   * @param type
   */
  @Override
  public Reflector findForClass(Class<?> type) {
    if (classCacheEnabled) {
            // synchronized (type) removed see issue #461
      Reflector cached = reflectorMap.get(type);
      if (cached == null) {
        cached = new Reflector(type);
        reflectorMap.put(type, cached);
      }
      return cached;
    } else {
      return new Reflector(type);
    }
  }

}
