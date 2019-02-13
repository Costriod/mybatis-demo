/**
 *    Copyright 2009-2017 the original author or authors.
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
package org.apache.ibatis.binding;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */
public class MapperMethod {

  private final SqlCommand command;
  private final MethodSignature method;

  /**
   * 构造函数，生成一个SqlCommand对象和MethodSignature对象
   * @param mapperInterface
   * @param method
   * @param config
   */
  public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
    this.command = new SqlCommand(config, mapperInterface, method);
    this.method = new MethodSignature(config, mapperInterface, method);
  }

  public Object execute(SqlSession sqlSession, Object[] args) {
    Object result;
    switch (command.getType()) {
      case INSERT: {
    	//返回需要填充到sql语句的参数
    	Object param = method.convertArgsToSqlCommandParam(args);
    	//command.getName()返回的就是MappedStatement的id属性，如com.xxx.xxx.Mapper.selectXXX的形式
        result = rowCountResult(sqlSession.insert(command.getName(), param));
        break;
      }
      case UPDATE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.update(command.getName(), param));
        break;
      }
      case DELETE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.delete(command.getName(), param));
        break;
      }
      case SELECT:
        if (method.returnsVoid() && method.hasResultHandler()) {
          executeWithResultHandler(sqlSession, args);
          result = null;
        } else if (method.returnsMany()) {
          result = executeForMany(sqlSession, args);
        } else if (method.returnsMap()) {
          result = executeForMap(sqlSession, args);
        } else if (method.returnsCursor()) {
          result = executeForCursor(sqlSession, args);
        } else {
          Object param = method.convertArgsToSqlCommandParam(args);
          result = sqlSession.selectOne(command.getName(), param);
        }
        break;
      case FLUSH:
        result = sqlSession.flushStatements();
        break;
      default:
        throw new BindingException("Unknown execution method for: " + command.getName());
    }
    if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
      throw new BindingException("Mapper method '" + command.getName() 
          + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
    }
    return result;
  }

  private Object rowCountResult(int rowCount) {
    final Object result;
    if (method.returnsVoid()) {
      result = null;
    } else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
      result = rowCount;
    } else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
      result = (long)rowCount;
    } else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
      result = rowCount > 0;
    } else {
      throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: " + method.getReturnType());
    }
    return result;
  }

  private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
    MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
    if (void.class.equals(ms.getResultMaps().get(0).getType())) {
      throw new BindingException("method " + command.getName() 
          + " needs either a @ResultMap annotation, a @ResultType annotation," 
          + " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
    }
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
    } else {
      sqlSession.select(command.getName(), param, method.extractResultHandler(args));
    }
  }

  private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
    List<E> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.<E>selectList(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.<E>selectList(command.getName(), param);
    }
    // issue #510 Collections & arrays support
    if (!method.getReturnType().isAssignableFrom(result.getClass())) {
      if (method.getReturnType().isArray()) {
        return convertToArray(result);
      } else {
        return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
      }
    }
    return result;
  }

  private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
    Cursor<T> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.<T>selectCursor(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.<T>selectCursor(command.getName(), param);
    }
    return result;
  }

  private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
    Object collection = config.getObjectFactory().create(method.getReturnType());
    MetaObject metaObject = config.newMetaObject(collection);
    metaObject.addAll(list);
    return collection;
  }

  @SuppressWarnings("unchecked")
  private <E> Object convertToArray(List<E> list) {
    Class<?> arrayComponentType = method.getReturnType().getComponentType();
    Object array = Array.newInstance(arrayComponentType, list.size());
    if (arrayComponentType.isPrimitive()) {
      for (int i = 0; i < list.size(); i++) {
        Array.set(array, i, list.get(i));
      }
      return array;
    } else {
      return list.toArray((E[])array);
    }
  }

  private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
    Map<K, V> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey(), rowBounds);
    } else {
      result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey());
    }
    return result;
  }

  public static class ParamMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = -2212268410512043556L;

    @Override
    public V get(Object key) {
      if (!super.containsKey(key)) {
        throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
      }
      return super.get(key);
    }

  }

  /**
   * 该类仅仅保存了MappedStatement的id和sqlCommandType属性
   */
  public static class SqlCommand {
	/**MappedStatement的id属性，类似这种格式：com.xxx.xxx.Mapper.selectXXX*/
    private final String name;
    /**MappedStatement的sqlCommandType，如select/update/insert/delete*/
    private final SqlCommandType type;

    /**
     * 该类仅仅保存了MappedStatement的id和sqlCommandType属性
     * @param configuration
     * @param mapperInterface
     * @param method
     */
    public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
      final String methodName = method.getName();
      final Class<?> declaringClass = method.getDeclaringClass();
      //从全局configuration里面获取一个MappedStatement对象
      MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass,
          configuration);
      if (ms == null) {//如果为null就是那种既没有与方法名对应的xml标签，又没有在方法上使用@Select @Insert @Update @Delete @SelectProvider注解的情况
        if (method.getAnnotation(Flush.class) != null) {//如果方法上面有Flush注解
          name = null;
          type = SqlCommandType.FLUSH;
        } else {//如果什么都没有
          throw new BindingException("Invalid bound statement (not found): "
              + mapperInterface.getName() + "." + methodName);
        }
      } else {
        name = ms.getId();
        type = ms.getSqlCommandType();
        if (type == SqlCommandType.UNKNOWN) {
          throw new BindingException("Unknown execution method for: " + name);
        }
      }
    }

    public String getName() {
      return name;
    }

    public SqlCommandType getType() {
      return type;
    }

    /**
     * 根据接口类型、方法名从configuration的缓存里面获取一个MappedStatement对象
     * @param mapperInterface 接口Class对象
     * @param methodName 方法名
     * @param declaringClass 声明methodName方法的类的Class对象
     * @param configuration 全局configuration
     * @return
     */
    private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName,
        Class<?> declaringClass, Configuration configuration) {
      //通过获取mapper接口全名加上一个方法名组装成一个字符串，如com.xxx.xxx.Mapper.selectXXX的形式
      String statementId = mapperInterface.getName() + "." + methodName;
      //前面解析mapper.xml标签的时候会生成一个个MappedStatement对象注册到configuration，其中key就是Mapper的namespace + . + 标签id，就是类似于com.xxx.xxx.Mapper.selectXXX的形式
      //还有一种可能是Mapper接口通过注解的形式（@Select @Insert @Update @Delete @SelectProvider）注册MappedStatement对象
      //如果configuration包含MappedStatement对象的话则直接返回
      if (configuration.hasStatement(statementId)) {
        return configuration.getMappedStatement(statementId);
      } else if (mapperInterface.equals(declaringClass)) {
        //如果前面的if条件不满足，那就只有一种情况，那就是这个方法既不是那种使用了注解的情况，也不是那种方法名和xml里面的标签id一一对应关系
        return null;
      }
      //如果前面条件依旧不满足，则说明这个方法不是当前接口声明的，是当前接口的父类接口声明的
      for (Class<?> superInterface : mapperInterface.getInterfaces()) {
        if (declaringClass.isAssignableFrom(superInterface)) {//declaringClass等于superInterface或者declaringClass是superInterface的父类
          MappedStatement ms = resolveMappedStatement(superInterface, methodName,
              declaringClass, configuration);//递归执行方法
          if (ms != null) {
            return ms;
          }
        }
      }
      return null;
    }
  }

  /**
   * 该类维护了方法的基础信息，如返回类型、参数映射关系等
   */
  public static class MethodSignature {
	/**如果方法返回的是集合*/
    private final boolean returnsMany;
    /**如果方法返回的是Map*/
    private final boolean returnsMap;
    /**如果方法是void类型*/
    private final boolean returnsVoid;
    /**如果方法返回的是Cursor*/
    private final boolean returnsCursor;
    /**方法返回类型*/
    private final Class<?> returnType;
    /**如果方法返回Map类型并且方法有MapKey注解，则mapKey不为空*/
    private final String mapKey;
    /**如果方法中有ResultHandler参数类型在，则返回ResultHandler参数的序号，如ResultHandler参数排在第2个，则返回的序号就是1*/
    private final Integer resultHandlerIndex;
    /**如果方法中有RowBounds参数类型在，则返回RowBounds参数的序号，如RowBounds参数排在第2个，则返回的序号就是1*/
    private final Integer rowBoundsIndex;
    /**维护方法的参数名序号与参数名的映射关系，key为参数的序号，如第1个参数，第2个参数；value为参数名*/
    private final ParamNameResolver paramNameResolver;

    /**
     * 该类维护了方法的基础信息，如返回类型、参数映射关系等
     * @param configuration
     * @param mapperInterface
     * @param method
     */
    public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
      Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
      if (resolvedReturnType instanceof Class<?>) {
        this.returnType = (Class<?>) resolvedReturnType;
      } else if (resolvedReturnType instanceof ParameterizedType) {
        this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
      } else {
        this.returnType = method.getReturnType();
      }
      this.returnsVoid = void.class.equals(this.returnType);
      this.returnsMany = (configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray());
      this.returnsCursor = Cursor.class.equals(this.returnType);
      this.mapKey = getMapKey(method);
      this.returnsMap = (this.mapKey != null);
      this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
      this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);
      this.paramNameResolver = new ParamNameResolver(configuration, method);
    }

    /**
     * 其实就是告诉调用者，实参args哪几个参数能够填充到sql语句里面，注意：RowBounds和ResultHandler两种类型参数会自动排除在外
     * @param args 实际执行时传过来的实际参数列表
     * @return
     */
    public Object convertArgsToSqlCommandParam(Object[] args) {
      return paramNameResolver.getNamedParams(args);
    }

    public boolean hasRowBounds() {
      return rowBoundsIndex != null;
    }

    public RowBounds extractRowBounds(Object[] args) {
      return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
    }

    public boolean hasResultHandler() {
      return resultHandlerIndex != null;
    }

    public ResultHandler extractResultHandler(Object[] args) {
      return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
    }

    public String getMapKey() {
      return mapKey;
    }

    public Class<?> getReturnType() {
      return returnType;
    }

    public boolean returnsMany() {
      return returnsMany;
    }

    public boolean returnsMap() {
      return returnsMap;
    }

    public boolean returnsVoid() {
      return returnsVoid;
    }

    public boolean returnsCursor() {
      return returnsCursor;
    }
    
    /**
     * 该方法返回paramType在method方法中的参数位置，如方法第二个参数类型是paramType，则本方法返回1
     * @param method
     * @param paramType
     * @return
     */
    private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
      Integer index = null;
      //方法参数类型
      final Class<?>[] argTypes = method.getParameterTypes();
      for (int i = 0; i < argTypes.length; i++) {
        if (paramType.isAssignableFrom(argTypes[i])) {//如果paramType是argTypes[i]类型或者paramType是argTypes[i]的父类
          if (index == null) {
            index = i;
          } else {
            throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
          }
        }
      }
      return index;
    }

    /**
     * 1.如果method返回Map类型的对象，并且方法上有MapKey注解，则返回注解的value属性
     * 2.否则返回null
     * @param method
     * @return
     */
    private String getMapKey(Method method) {
      String mapKey = null;
      if (Map.class.isAssignableFrom(method.getReturnType())) {//如果方法返回类型是Map
        final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
        if (mapKeyAnnotation != null) {
          mapKey = mapKeyAnnotation.value();
        }
      }
      return mapKey;
    }
  }

}
