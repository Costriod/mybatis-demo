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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 * 每个mapper.xml都对应一个XMLMapperBuilder，而每个XMLMapperBuilder都包含一个MapperBuilderAssistant对象
 */
public class XMLMapperBuilder extends BaseBuilder {

  private final XPathParser parser;
  private final MapperBuilderAssistant builderAssistant;
  /**全局configuration对象的sqlFragments属性*/
  private final Map<String, XNode> sqlFragments;
  /**xml文件路径*/
  private final String resource;

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  /**
   * 
   * @param inputStream xml文件输入流
   * @param configuration 全局configuration对象
   * @param resource Mapper.xml文件路径
   * @param sqlFragments configuration对象的sqlFragments
   * @param namespace namespace属性字符串，每个mapper都有全局唯一的namespace
   */
  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
  }

  /**
   * 解析xml，如果在解析过程中有一些没有适配成功的ResultMap、CacheRef、MappedStatement，方法最后都会重新解析全局configuration下面的incompleteResultMaps、incompleteCacheRefs、incompleteStatements
   */
  public void parse() {
    if (!configuration.isResourceLoaded(resource)) {
      //解析整个xml
      configurationElement(parser.evalNode("/mapper"));
      //解析完之后，addLoadedResource表示该文件已被解析过，下次就不会重复解析
      configuration.addLoadedResource(resource);
      //如果Mapper类还没生成过代理工厂，那么这里生成代理工厂并绑定到Mapper类
      bindMapperForNamespace();
    }
    
    //处理之前还没适配的ResultMap，重新执行一次ResultMap适配
    parsePendingResultMaps();
    //处理之前还没适配的CacheRef，重新执行一次CacheRef适配
    parsePendingCacheRefs();
    //处理之前还没初始化成功的MappedStatement，重新执行一次MappedStatement解析初始化
    parsePendingStatements();
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }

  /**
   * 解析整个xml
   * 1.首先解析cacheRef，如果引用的cache还没初始化也没关系，因为文件读取顺序总有先后
   * 2.解析cache标签，设置当前namespace下面的cache对象，后面将cache添加到configuration
   * 3.解析parameterMap，parameterMap已废弃！老式风格的参数映射
   * 4.解析resultMap，最终resultMap都会加入到configuration里面
   * 5.解析sql标签，最终存入到configuration的sqlFragments属性里面
   * 6.解析所有的select|insert|update|delete标签，将生成的MappedStatement写入到configuration.mappedStatements里面
   * @param context
   */
  private void configurationElement(XNode context) {
    try {
      //找到xml文件的namespace属性
      String namespace = context.getStringAttribute("namespace");
      if (namespace == null || namespace.equals("")) {
        throw new BuilderException("Mapper's namespace cannot be empty");
      }
      builderAssistant.setCurrentNamespace(namespace);
      //1.设置cache-ref引用关系，key为源namespace，value为目标namespace
      //2.同时MapperBuilderAssistant对象会引用一个目标namespace的缓存对象
      cacheRefElement(context.evalNode("cache-ref"));
      //解析cache标签，重置当前namespace的cache对象，将cache添加到configuration
      cacheElement(context.evalNode("cache"));
      //parameterMap已废弃！老式风格的参数映射
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
      //结果集字段与类属性映射关系，最终resultMap都会加入到configuration里面
      resultMapElements(context.evalNodes("/mapper/resultMap"));
      //解析sql标签，最终存入到configuration的sqlFragments属性里面
      sqlElement(context.evalNodes("/mapper/sql"));
      //解析所有的select|insert|update|delete标签，将生成的MappedStatement写入到configuration.mappedStatements里面
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. Cause: " + e, e);
    }
  }

  /**
   * 解析所有的select|insert|update|delete标签，将生成的MappedStatement写入到configuration.mappedStatements里面
   * @param list select|insert|update|delete标签数组
   */
  private void buildStatementFromContext(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    buildStatementFromContext(list, null);
  }

  /**
   * 1.解析xml里面所有的select|insert|update|delete标签，然后生成一个MappedStatement对象，并把该对象写入到configuration.mappedStatements里面
   * 2.如果insert标签内部有SelectKey标签的情况下，这也会生成一个MappedStatement对象，并把该对象写入到configuration.mappedStatements里面，只不过key是外层insert标签的id + !selectKey
   * @param list
   * @param requiredDatabaseId
   */
  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  /**
   * 其实这里就是对之前还没适配的ResultMap，重新执行一次ResultMap适配
   */
  private void parsePendingResultMaps() {
	//读取出configuration没有适配的ResultMaps，因为有些ResultMap有extend的父ResultMap，但是父ResultMap还没初始化
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
        }
      }
    }
  }

  /**
   * 其实这里处理之前还没适配的CacheRef，重新执行一次CacheRef适配操作
   */
  private void parsePendingCacheRefs() {
    Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
    synchronized (incompleteCacheRefs) {
      Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolveCacheRef();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Cache ref is still missing a resource...
        }
      }
    }
  }

  private void parsePendingStatements() {
    Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
    synchronized (incompleteStatements) {
      Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().parseStatementNode();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Statement is still missing a resource...
        }
      }
    }
  }
  
  /**
   * 1.设置cache-ref引用关系，key为源namespace，value为目标namespace
   * 2.同时MapperBuilderAssistant对象会引用一个目标namespace的缓存对象
   * 需注意，cache-ref引用另外一个namespace，如果另外一个namespace还没开始初始化（mybatis按文件读取先后顺序初始化），会抛出IncompleteElementException，然后后面的catch会捕获这个异常并处理
   * @param context
   */
  private void cacheRefElement(XNode context) {
    if (context != null) {
      //key为当前namespace，value为引用的namespace
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
      try {
        cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
        configuration.addIncompleteCacheRef(cacheRefResolver);
      }
    }
  }

  /**
   * 解析cache标签，重置当前namespace的cache对象
   * @param context
   * @throws Exception
   */
  private void cacheElement(XNode context) throws Exception {
    if (context != null) {
      //<cache />标签下的type属性，如果不存在type则默认为PERPETUAL
      String type = context.getStringAttribute("type", "PERPETUAL");
      //从typeAliasRegistry获取cache的实现类对象
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
      //<cache />标签下的eviction属性，如果不存在eviction则默认为LRU
      String eviction = context.getStringAttribute("eviction", "LRU");
      //从typeAliasRegistry获取eviction的实现类对象
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
      //<cache />标签下的flushInterval属性，也就是缓存刷新间隔时间
      Long flushInterval = context.getLongAttribute("flushInterval");
      //<cache />标签下的size属性，也就是缓存容量
      Integer size = context.getIntAttribute("size");
      //<cache />标签下的readOnly属性，如果未设置，则默认为false，readWrite则是readOnly取反
      boolean readWrite = !context.getBooleanAttribute("readOnly", false);
      //<cache />标签下的blocking属性，如果未设置，则默认为false
      boolean blocking = context.getBooleanAttribute("blocking", false);
      //<cache />标签下的property属性
      Properties props = context.getChildrenAsProperties();
      //重置MapperBuilderAssistant的缓存对象，并且重置该namespace的cache对象
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
    }
  }

  private void parameterMapElement(List<XNode> list) throws Exception {
    for (XNode parameterMapNode : list) {
      String id = parameterMapNode.getStringAttribute("id");
      String type = parameterMapNode.getStringAttribute("type");
      Class<?> parameterClass = resolveClass(type);
      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
      for (XNode parameterNode : parameterNodes) {
        String property = parameterNode.getStringAttribute("property");
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");
        String mode = parameterNode.getStringAttribute("mode");
        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale");
        ParameterMode modeEnum = resolveParameterMode(mode);
        Class<?> javaTypeClass = resolveClass(javaType);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping);
      }
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  /**
   * 处理mapper.xml解析出来的ResultMap标签列表
   * @param list
   * @throws Exception
   */
  private void resultMapElements(List<XNode> list) throws Exception {
    for (XNode resultMapNode : list) {
      try {
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  /**
   * 处理单个ResultMap标签
   * @param resultMapNode
   * @return
   * @throws Exception
   */
  private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
    return resultMapElement(resultMapNode, Collections.<ResultMapping> emptyList());
  }

  /**
   * 处理单个ResultMap标签，最终configuration会把这个ResultMap对象存入map中，key:  namespace.resultMapId，value为ResultMap
   * 如果ResultMap有extend属性，并且extend的父ResultMap还没初始化，此时就会抛出IncompleteElementException，并被这里catch到，然后添加到incompleteResultMaps里面
   * @param resultMapNode
   * @param additionalResultMappings
   * @return
   * @throws Exception
   */
  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings) throws Exception {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
    //一般ResultMap标签都有id和type标签
    String id = resultMapNode.getStringAttribute("id",
        resultMapNode.getValueBasedIdentifier());
    String type = resultMapNode.getStringAttribute("type",
        resultMapNode.getStringAttribute("ofType",
            resultMapNode.getStringAttribute("resultType",
                resultMapNode.getStringAttribute("javaType"))));
    String extend = resultMapNode.getStringAttribute("extends");
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
    Class<?> typeClass = resolveClass(type);
    Discriminator discriminator = null;
    List<ResultMapping> resultMappings = new ArrayList<ResultMapping>();
    resultMappings.addAll(additionalResultMappings);
    List<XNode> resultChildren = resultMapNode.getChildren();
    for (XNode resultChild : resultChildren) {
      if ("constructor".equals(resultChild.getName())) {
        processConstructorElement(resultChild, typeClass, resultMappings);
      } else if ("discriminator".equals(resultChild.getName())) {
        discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
      } else {
        List<ResultFlag> flags = new ArrayList<ResultFlag>();
        if ("id".equals(resultChild.getName())) {
          flags.add(ResultFlag.ID);
        }
        resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
      }
    }
    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
      return resultMapResolver.resolve();
    } catch (IncompleteElementException  e) {
      //如果ResultMap有extend属性，并且extend的父ResultMap还没初始化，此时就会抛出IncompleteElementException，并被这里catch到
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    List<XNode> argChildren = resultChild.getChildren();
    for (XNode argChild : argChildren) {
      List<ResultFlag> flags = new ArrayList<ResultFlag>();
      flags.add(ResultFlag.CONSTRUCTOR);
      if ("idArg".equals(argChild.getName())) {
        flags.add(ResultFlag.ID);
      }
      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String typeHandler = context.getStringAttribute("typeHandler");
    Class<?> javaTypeClass = resolveClass(javaType);
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    Map<String, String> discriminatorMap = new HashMap<String, String>();
    for (XNode caseChild : context.getChildren()) {
      String value = caseChild.getStringAttribute("value");
      String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings));
      discriminatorMap.put(value, resultMap);
    }
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }

  private void sqlElement(List<XNode> list) throws Exception {
    if (configuration.getDatabaseId() != null) {
      sqlElement(list, configuration.getDatabaseId());
    }
    sqlElement(list, null);
  }

  /**
   * 解析sql标签列表，最终put到configuration的sqlFragments，其中key: namespace.sqlId, value: XNode原始xml对象
   * @param list sql标签列表
   * @param requiredDatabaseId
   * @throws Exception
   */
  private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
    for (XNode context : list) {
      String databaseId = context.getStringAttribute("databaseId");
      String id = context.getStringAttribute("id");
      id = builderAssistant.applyCurrentNamespace(id, false);
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
        sqlFragments.put(id, context);
      }
    }
  }
  
  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      if (!requiredDatabaseId.equals(databaseId)) {
        return false;
      }
    } else {
      if (databaseId != null) {
        return false;
      }
      // skip this fragment if there is a previous one with a not null databaseId
      if (this.sqlFragments.containsKey(id)) {
        XNode context = this.sqlFragments.get(id);
        if (context.getStringAttribute("databaseId") != null) {
          return false;
        }
      }
    }
    return true;
  }

  private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
    String property;
    if (flags.contains(ResultFlag.CONSTRUCTOR)) {
      property = context.getStringAttribute("name");
    } else {
      property = context.getStringAttribute("property");
    }
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String nestedSelect = context.getStringAttribute("select");
    String nestedResultMap = context.getStringAttribute("resultMap",
        processNestedResultMappings(context, Collections.<ResultMapping> emptyList()));
    String notNullColumn = context.getStringAttribute("notNullColumn");
    String columnPrefix = context.getStringAttribute("columnPrefix");
    String typeHandler = context.getStringAttribute("typeHandler");
    String resultSet = context.getStringAttribute("resultSet");
    String foreignColumn = context.getStringAttribute("foreignColumn");
    boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
    Class<?> javaTypeClass = resolveClass(javaType);
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
  }
  
  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings) throws Exception {
    if ("association".equals(context.getName())
        || "collection".equals(context.getName())
        || "case".equals(context.getName())) {
      if (context.getStringAttribute("select") == null) {
        ResultMap resultMap = resultMapElement(context, resultMappings);
        return resultMap.getId();
      }
    }
    return null;
  }

  /**
   * 仅针对那种Mapper类还没生成代理工厂的情况
   */
  private void bindMapperForNamespace() {
    String namespace = builderAssistant.getCurrentNamespace();
    if (namespace != null) {
      Class<?> boundType = null;
      try {
        boundType = Resources.classForName(namespace);
      } catch (ClassNotFoundException e) {
        //ignore, bound type is not required
      }
      //到这一步，如果Mapper类已存在
      if (boundType != null) {
    	//但是Mapper类还没生成代理工厂的情况
        if (!configuration.hasMapper(boundType)) {
          // Spring may not know the real resource name so we set a flag
          // to prevent loading again this resource from the mapper interface
          // look at MapperAnnotationBuilder#loadXmlResource
          //addLoadedResource表示该namespace此时已经被解析，后续不会重复解析
          configuration.addLoadedResource("namespace:" + namespace);
          //生成代理类对象
          configuration.addMapper(boundType);
        }
      }
    }
  }

}
