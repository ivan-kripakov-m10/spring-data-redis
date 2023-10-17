/*
 * Copyright 2011-2023 the original author or authors.
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
package org.springframework.data.redis.core;

import java.io.Closeable;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.connection.RedisTxCommands;
import org.springframework.data.redis.connection.SortParameters;
import org.springframework.data.redis.connection.zset.Tuple;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.query.QueryUtils;
import org.springframework.data.redis.core.query.SortQuery;
import org.springframework.data.redis.core.script.DefaultScriptExecutor;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.script.ScriptExecutor;
import org.springframework.data.redis.core.types.RedisClientInfo;
import org.springframework.data.redis.hash.HashMapper;
import org.springframework.data.redis.hash.ObjectHashMapper;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationUtils;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.lang.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;

/**
 * Helper class that simplifies Redis data access code.
 * <p>
 * Performs automatic serialization/deserialization between the given objects and the underlying binary data in the
 * Redis store. By default, it uses Java serialization for its objects (through {@link JdkSerializationRedisSerializer}
 * ). For String intensive operations consider the dedicated {@link StringRedisTemplate}.
 * <p>
 * The central method is execute, supporting Redis access code implementing the {@link RedisCallback} interface. It
 * provides {@link RedisConnection} handling such that neither the {@link RedisCallback} implementation nor the calling
 * code needs to explicitly care about retrieving/closing Redis connections, or handling Connection lifecycle
 * exceptions. For typical single step actions, there are various convenience methods.
 * <p>
 * Once configured, this class is thread-safe.
 * <p>
 * Note that while the template is generified, it is up to the serializers/deserializers to properly convert the given
 * Objects to and from binary data.
 * <p>
 * <b>This is the central class in Redis support</b>.
 *
 * @author Costin Leau
 * @author Christoph Strobl
 * @author Ninad Divadkar
 * @author Anqing Shao
 * @author Mark Paluch
 * @author Denis Zavedeev
 * @author ihaohong
 * @author Chen Li
 * @author Vedran Pavic
 * @param <K> the Redis key type against which the template works (usually a String)
 * @param <V> the Redis value type against which the template works
 * @see StringRedisTemplate
 */
public class RedisTemplate<K, V> extends RedisAccessor implements RedisOperations<K, V>, BeanClassLoaderAware {

	private boolean enableTransactionSupport = false;
	private boolean exposeConnection = false;
	private boolean initialized = false;
	private boolean enableDefaultSerializer = true;
	private @Nullable RedisSerializer<?> defaultSerializer;
	private @Nullable ClassLoader classLoader;

	@SuppressWarnings("rawtypes") private @Nullable RedisSerializer keySerializer = null;
	@SuppressWarnings("rawtypes") private @Nullable RedisSerializer valueSerializer = null;
	@SuppressWarnings("rawtypes") private @Nullable RedisSerializer hashKeySerializer = null;
	@SuppressWarnings("rawtypes") private @Nullable RedisSerializer hashValueSerializer = null;
	private RedisSerializer<String> stringSerializer = RedisSerializer.string();

	private @Nullable ScriptExecutor<K> scriptExecutor;

	private final BoundOperationsProxyFactory boundOperations = new BoundOperationsProxyFactory();
	private final ValueOperations<K, V> valueOps = new DefaultValueOperations<>(this);
	private final ListOperations<K, V> listOps = new DefaultListOperations<>(this);
	private final SetOperations<K, V> setOps = new DefaultSetOperations<>(this);
	private final StreamOperations<K, ?, ?> streamOps = new DefaultStreamOperations<>(this,
			ObjectHashMapper.getSharedInstance());
	private final ZSetOperations<K, V> zSetOps = new DefaultZSetOperations<>(this);
	private final GeoOperations<K, V> geoOps = new DefaultGeoOperations<>(this);
	private final HyperLogLogOperations<K, V> hllOps = new DefaultHyperLogLogOperations<>(this);
	private final ClusterOperations<K, V> clusterOps = new DefaultClusterOperations<>(this);

	/**
	 * Constructs a new <code>RedisTemplate</code> instance.
	 */
	public RedisTemplate() {}

	@Override
	public void afterPropertiesSet() {

		super.afterPropertiesSet();

		boolean defaultUsed = false;

		if (defaultSerializer == null) {

			defaultSerializer = new JdkSerializationRedisSerializer(
					classLoader != null ? classLoader : this.getClass().getClassLoader());
		}

		if (enableDefaultSerializer) {

			if (keySerializer == null) {
				keySerializer = defaultSerializer;
				defaultUsed = true;
			}
			if (valueSerializer == null) {
				valueSerializer = defaultSerializer;
				defaultUsed = true;
			}
			if (hashKeySerializer == null) {
				hashKeySerializer = defaultSerializer;
				defaultUsed = true;
			}
			if (hashValueSerializer == null) {
				hashValueSerializer = defaultSerializer;
				defaultUsed = true;
			}
		}

		if (enableDefaultSerializer && defaultUsed) {
			Assert.notNull(defaultSerializer, "default serializer null and not all serializers initialized");
		}

		if (scriptExecutor == null) {
			this.scriptExecutor = new DefaultScriptExecutor<>(this);
		}

		initialized = true;
	}

	/**
	 * Returns whether to expose the native Redis connection to RedisCallback code, or rather a connection proxy (the
	 * default).
	 *
	 * @return whether to expose the native Redis connection or not
	 */
	public boolean isExposeConnection() {
		return exposeConnection;
	}

	/**
	 * Sets whether to expose the Redis connection to {@link RedisCallback} code. Default is "false": a proxy will be
	 * returned, suppressing {@code quit} and {@code disconnect} calls.
	 *
	 * @param exposeConnection
	 */
	public void setExposeConnection(boolean exposeConnection) {
		this.exposeConnection = exposeConnection;
	}

	/**
	 * @return Whether or not the default serializer should be used. If not, any serializers not explicitly set will
	 *         remain null and values will not be serialized or deserialized.
	 */
	public boolean isEnableDefaultSerializer() {
		return enableDefaultSerializer;
	}

	/**
	 * @param enableDefaultSerializer Whether or not the default serializer should be used. If not, any serializers not
	 *          explicitly set will remain null and values will not be serialized or deserialized.
	 */
	public void setEnableDefaultSerializer(boolean enableDefaultSerializer) {
		this.enableDefaultSerializer = enableDefaultSerializer;
	}

	/**
	 * If set to {@code true} {@link RedisTemplate} will participate in ongoing transactions using
	 * {@literal MULTI...EXEC|DISCARD} to keep track of operations.
	 *
	 * @param enableTransactionSupport whether to participate in ongoing transactions.
	 * @since 1.3
	 * @see RedisConnectionUtils#getConnection(RedisConnectionFactory, boolean)
	 * @see TransactionSynchronizationManager#isActualTransactionActive()
	 */
	public void setEnableTransactionSupport(boolean enableTransactionSupport) {
		this.enableTransactionSupport = enableTransactionSupport;
	}

	/**
	 * Set the {@link ClassLoader} to be used for the default {@link JdkSerializationRedisSerializer} in case no other
	 * {@link RedisSerializer} is explicitly set as the default one.
	 *
	 * @param classLoader can be {@literal null}.
	 * @see org.springframework.beans.factory.BeanClassLoaderAware#setBeanClassLoader
	 * @since 1.8
	 */
	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	/**
	 * Returns the default serializer used by this template.
	 *
	 * @return template default serializer
	 */
	@Nullable
	public RedisSerializer<?> getDefaultSerializer() {
		return defaultSerializer;
	}

	/**
	 * Sets the default serializer to use for this template. All serializers (except the
	 * {@link #setStringSerializer(RedisSerializer)}) are initialized to this value unless explicitly set. Defaults to
	 * {@link JdkSerializationRedisSerializer}.
	 *
	 * @param serializer default serializer to use
	 */
	public void setDefaultSerializer(RedisSerializer<?> serializer) {
		this.defaultSerializer = serializer;
	}

	/**
	 * Sets the key serializer to be used by this template. Defaults to {@link #getDefaultSerializer()}.
	 *
	 * @param serializer the key serializer to be used by this template.
	 */
	public void setKeySerializer(RedisSerializer<?> serializer) {
		this.keySerializer = serializer;
	}

	/**
	 * Returns the key serializer used by this template.
	 *
	 * @return the key serializer used by this template.
	 */
	@Override
	public RedisSerializer<?> getKeySerializer() {
		return keySerializer;
	}

	/**
	 * Sets the value serializer to be used by this template. Defaults to {@link #getDefaultSerializer()}.
	 *
	 * @param serializer the value serializer to be used by this template.
	 */
	public void setValueSerializer(RedisSerializer<?> serializer) {
		this.valueSerializer = serializer;
	}

	/**
	 * Returns the value serializer used by this template.
	 *
	 * @return the value serializer used by this template.
	 */
	@Override
	public RedisSerializer<?> getValueSerializer() {
		return valueSerializer;
	}

	/**
	 * Returns the hashKeySerializer.
	 *
	 * @return Returns the hashKeySerializer
	 */
	@Override
	public RedisSerializer<?> getHashKeySerializer() {
		return hashKeySerializer;
	}

	/**
	 * Sets the hash key (or field) serializer to be used by this template. Defaults to {@link #getDefaultSerializer()}.
	 *
	 * @param hashKeySerializer The hashKeySerializer to set.
	 */
	public void setHashKeySerializer(RedisSerializer<?> hashKeySerializer) {
		this.hashKeySerializer = hashKeySerializer;
	}

	/**
	 * Returns the hashValueSerializer.
	 *
	 * @return Returns the hashValueSerializer
	 */
	@Override
	public RedisSerializer<?> getHashValueSerializer() {
		return hashValueSerializer;
	}

	/**
	 * Sets the hash value serializer to be used by this template. Defaults to {@link #getDefaultSerializer()}.
	 *
	 * @param hashValueSerializer The hashValueSerializer to set.
	 */
	public void setHashValueSerializer(RedisSerializer<?> hashValueSerializer) {
		this.hashValueSerializer = hashValueSerializer;
	}

	/**
	 * Returns the stringSerializer.
	 *
	 * @return Returns the stringSerializer
	 */
	public RedisSerializer<String> getStringSerializer() {
		return stringSerializer;
	}

	/**
	 * Sets the string value serializer to be used by this template (when the arguments or return types are always
	 * strings). Defaults to {@link StringRedisSerializer}.
	 *
	 * @see ValueOperations#get(Object, long, long)
	 * @param stringSerializer The stringValueSerializer to set.
	 */
	public void setStringSerializer(RedisSerializer<String> stringSerializer) {
		this.stringSerializer = stringSerializer;
	}

	/**
	 * @param scriptExecutor The {@link ScriptExecutor} to use for executing Redis scripts
	 */
	public void setScriptExecutor(ScriptExecutor<K> scriptExecutor) {
		this.scriptExecutor = scriptExecutor;
	}

	@Override
	@Nullable
	public <T> T execute(RedisCallback<T> action) {
		return execute(action, isExposeConnection());
	}

	/**
	 * Executes the given action object within a connection, which can be exposed or not.
	 *
	 * @param <T> return type
	 * @param action callback object that specifies the Redis action
	 * @param exposeConnection whether to enforce exposure of the native Redis Connection to callback code
	 * @return object returned by the action
	 */
	@Nullable
	public <T> T execute(RedisCallback<T> action, boolean exposeConnection) {
		return execute(action, exposeConnection, false);
	}

	/**
	 * Executes the given action object within a connection that can be exposed or not. Additionally, the connection can
	 * be pipelined. Note the results of the pipeline are discarded (making it suitable for write-only scenarios).
	 *
	 * @param <T> return type
	 * @param action callback object to execute
	 * @param exposeConnection whether to enforce exposure of the native Redis Connection to callback code
	 * @param pipeline whether to pipeline or not the connection for the execution
	 * @return object returned by the action
	 */
	@Nullable
	public <T> T execute(RedisCallback<T> action, boolean exposeConnection, boolean pipeline) {

		Assert.isTrue(initialized, "template not initialized; call afterPropertiesSet() before using it");
		Assert.notNull(action, "Callback object must not be null");

		RedisConnectionFactory factory = getRequiredConnectionFactory();
		RedisConnection conn = RedisConnectionUtils.getConnection(factory, enableTransactionSupport);

		try {

			boolean existingConnection = TransactionSynchronizationManager.hasResource(factory);
			RedisConnection connToUse = preProcessConnection(conn, existingConnection);

			boolean pipelineStatus = connToUse.isPipelined();
			if (pipeline && !pipelineStatus) {
				connToUse.openPipeline();
			}

			RedisConnection connToExpose = (exposeConnection ? connToUse : createRedisConnectionProxy(connToUse));
			T result = action.doInRedis(connToExpose);

			// close pipeline
			if (pipeline && !pipelineStatus) {
				connToUse.closePipeline();
			}

			return postProcessResult(result, connToUse, existingConnection);
		} finally {
			RedisConnectionUtils.releaseConnection(conn, factory);
		}
	}

	@Override
	public <T> T execute(SessionCallback<T> session) {

		Assert.isTrue(initialized, "template not initialized; call afterPropertiesSet() before using it");
		Assert.notNull(session, "Callback object must not be null");

		RedisConnectionFactory factory = getRequiredConnectionFactory();
		// bind connection
		RedisConnectionUtils.bindConnection(factory, enableTransactionSupport);
		try {
			return session.execute(this);
		} finally {
			RedisConnectionUtils.unbindConnection(factory);
		}
	}

	@Override
	public List<Object> executePipelined(SessionCallback<?> session) {
		return executePipelined(session, valueSerializer);
	}

	@Override
	public List<Object> executePipelined(SessionCallback<?> session, @Nullable RedisSerializer<?> resultSerializer) {

		Assert.isTrue(initialized, "template not initialized; call afterPropertiesSet() before using it");
		Assert.notNull(session, "Callback object must not be null");

		RedisConnectionFactory factory = getRequiredConnectionFactory();
		// bind connection
		RedisConnectionUtils.bindConnection(factory, enableTransactionSupport);
		try {
			return execute((RedisCallback<List<Object>>) connection -> {
				connection.openPipeline();
				boolean pipelinedClosed = false;
				try {
					Object result = executeSession(session);
					if (result != null) {
						throw new InvalidDataAccessApiUsageException(
								"Callback cannot return a non-null value as it gets overwritten by the pipeline");
					}
					List<Object> closePipeline = connection.closePipeline();
					pipelinedClosed = true;
					return deserializeMixedResults(closePipeline, resultSerializer, hashKeySerializer, hashValueSerializer);
				} finally {
					if (!pipelinedClosed) {
						connection.closePipeline();
					}
				}
			});
		} finally {
			RedisConnectionUtils.unbindConnection(factory);
		}
	}

	@Override
	public List<Object> executePipelined(RedisCallback<?> action) {
		return executePipelined(action, valueSerializer);
	}

	@Override
	public List<Object> executePipelined(RedisCallback<?> action, @Nullable RedisSerializer<?> resultSerializer) {

		return execute((RedisCallback<List<Object>>) connection -> {
			connection.openPipeline();
			boolean pipelinedClosed = false;
			try {
				Object result = action.doInRedis(connection);
				if (result != null) {
					throw new InvalidDataAccessApiUsageException(
							"Callback cannot return a non-null value as it gets overwritten by the pipeline");
				}
				List<Object> closePipeline = connection.closePipeline();
				pipelinedClosed = true;
				return deserializeMixedResults(closePipeline, resultSerializer, hashKeySerializer, hashValueSerializer);
			} finally {
				if (!pipelinedClosed) {
					connection.closePipeline();
				}
			}
		});
	}

	@Override
	public <T> T execute(RedisScript<T> script, List<K> keys, Object... args) {
		return scriptExecutor.execute(script, keys, args);
	}

	@Override
	public <T> T execute(RedisScript<T> script, RedisSerializer<?> argsSerializer, RedisSerializer<T> resultSerializer,
			List<K> keys, Object... args) {
		return scriptExecutor.execute(script, argsSerializer, resultSerializer, keys, args);
	}

	@Override
	public <T extends Closeable> T executeWithStickyConnection(RedisCallback<T> callback) {

		Assert.isTrue(initialized, "template not initialized; call afterPropertiesSet() before using it");
		Assert.notNull(callback, "Callback object must not be null");

		RedisConnectionFactory factory = getRequiredConnectionFactory();

		RedisConnection connection = preProcessConnection(RedisConnectionUtils.doGetConnection(factory, true, false, false),
				false);

		return callback.doInRedis(connection);
	}

	private Object executeSession(SessionCallback<?> session) {
		return session.execute(this);
	}

	protected RedisConnection createRedisConnectionProxy(RedisConnection connection) {

		Class<?>[] ifcs = ClassUtils.getAllInterfacesForClass(connection.getClass(), getClass().getClassLoader());

		return (RedisConnection) Proxy.newProxyInstance(connection.getClass().getClassLoader(), ifcs,
				new CloseSuppressingInvocationHandler(connection));
	}

	/**
	 * Processes the connection (before any settings are executed on it). Default implementation returns the connection as
	 * is.
	 *
	 * @param connection redis connection
	 */
	protected RedisConnection preProcessConnection(RedisConnection connection, boolean existingConnection) {
		return connection;
	}

	@Nullable
	protected <T> T postProcessResult(@Nullable T result, RedisConnection conn, boolean existingConnection) {
		return result;
	}

	// -------------------------------------------------------------------------
	// Methods dealing with Redis Keys
	// -------------------------------------------------------------------------

	@Override
	public Boolean copy(K from, K to, boolean replace) {

		Assert.notNull(from, "From-key must not be null");
		Assert.notNull(to, "To-key must not be null");

		byte[] sourceKey = rawKey(from);
		byte[] targetKey = rawKey(to);

		return doWithKeys(connection -> connection.copy(sourceKey, targetKey, replace));
	}

	@Override
	public Boolean hasKey(K key) {

		byte[] rawKey = rawKey(key);

		return doWithKeys(connection -> connection.exists(rawKey));
	}

	@Override
	public Long countExistingKeys(Collection<K> keys) {

		Assert.notNull(keys, "Keys must not be null");

		byte[][] rawKeys = rawKeys(keys);
		return doWithKeys(connection -> connection.exists(rawKeys));
	}

	@Override
	public Boolean delete(K key) {

		byte[] rawKey = rawKey(key);

		Long result = doWithKeys(connection -> connection.del(rawKey));
		return result != null && result.intValue() == 1;
	}

	@Override
	public Long delete(Collection<K> keys) {

		if (CollectionUtils.isEmpty(keys)) {
			return 0L;
		}

		byte[][] rawKeys = rawKeys(keys);

		return doWithKeys(connection -> connection.del(rawKeys));
	}

	@Override
	public Boolean unlink(K key) {

		byte[] rawKey = rawKey(key);

		Long result = doWithKeys(connection -> connection.unlink(rawKey));

		return result != null && result.intValue() == 1;
	}

	@Override
	public Long unlink(Collection<K> keys) {

		if (CollectionUtils.isEmpty(keys)) {
			return 0L;
		}

		byte[][] rawKeys = rawKeys(keys);

		return doWithKeys(connection -> connection.unlink(rawKeys));
	}

	@Override
	public DataType type(K key) {

		byte[] rawKey = rawKey(key);
		return doWithKeys(connection -> connection.type(rawKey));
	}

	@Override
	@SuppressWarnings("unchecked")
	public Set<K> keys(K pattern) {

		byte[] rawKey = rawKey(pattern);
		Set<byte[]> rawKeys = doWithKeys(connection -> connection.keys(rawKey));

		return keySerializer != null ? SerializationUtils.deserialize(rawKeys, keySerializer) : (Set<K>) rawKeys;
	}

	@Override
	public Cursor<K> scan(ScanOptions options) {
		Assert.notNull(options, "ScanOptions must not be null");

		return executeWithStickyConnection(
				(RedisCallback<Cursor<K>>) connection -> new ConvertingCursor<>(connection.scan(options),
						this::deserializeKey));
	}

	@Override
	public K randomKey() {

		byte[] rawKey = doWithKeys(RedisKeyCommands::randomKey);
		return deserializeKey(rawKey);
	}

	@Override
	public void rename(K from, K to) {

		Assert.notNull(from, "From-key must not be null");
		Assert.notNull(to, "To-key must not be null");

		byte[] rawFrom = rawKey(from);
		byte[] rawTo = rawKey(to);

		doWithKeys(connection -> {
			connection.rename(rawFrom, rawTo);
			return null;
		});
	}

	@Override
	public Boolean renameIfAbsent(K from, K to) {

		Assert.notNull(from, "From-key must not be null");
		Assert.notNull(to, "To-key must not be null");

		byte[] rawFrom = rawKey(from);
		byte[] rawTo = rawKey(to);
		return doWithKeys(connection -> connection.renameNX(rawFrom, rawTo));
	}

	@Override
	public Boolean expire(K key, long timeout, TimeUnit unit) {

		byte[] rawKey = rawKey(key);
		long rawTimeout = TimeoutUtils.toMillis(timeout, unit);

		return doWithKeys(connection -> {
			try {
				return connection.pExpire(rawKey, rawTimeout);
			} catch (Exception e) {
				// Driver may not support pExpire or we may be running on Redis 2.4
				return connection.expire(rawKey, TimeoutUtils.toSeconds(timeout, unit));
			}
		});
	}

	@Override
	public Boolean expireAt(K key, Date date) {

		byte[] rawKey = rawKey(key);

		return doWithKeys(connection -> {
			try {
				return connection.pExpireAt(rawKey, date.getTime());
			} catch (Exception e) {
				return connection.expireAt(rawKey, date.getTime() / 1000);
			}
		});
	}

	@Override
	public Boolean persist(K key) {

		byte[] rawKey = rawKey(key);
		return doWithKeys(connection -> connection.persist(rawKey));
	}

	@Override
	public Long getExpire(K key) {

		byte[] rawKey = rawKey(key);
		return doWithKeys(connection -> connection.ttl(rawKey));
	}

	@Override
	public Long getExpire(K key, TimeUnit timeUnit) {

		byte[] rawKey = rawKey(key);
		return doWithKeys(connection -> {
			try {
				return connection.pTtl(rawKey, timeUnit);
			} catch (Exception e) {
				// Driver may not support pTtl or we may be running on Redis 2.4
				return connection.ttl(rawKey, timeUnit);
			}
		});
	}

	@Override
	public Boolean move(K key, int dbIndex) {

		byte[] rawKey = rawKey(key);
		return doWithKeys(connection -> connection.move(rawKey, dbIndex));
	}

	/**
	 * Executes the Redis dump command and returns the results. Redis uses a non-standard serialization mechanism and
	 * includes checksum information, thus the raw bytes are returned as opposed to deserializing with valueSerializer.
	 * Use the return value of dump as the value argument to restore
	 *
	 * @param key The key to dump
	 * @return results The results of the dump operation
	 */
	@Override
	public byte[] dump(K key) {

		byte[] rawKey = rawKey(key);
		return doWithKeys(connection -> connection.dump(rawKey));
	}

	/**
	 * Executes the Redis restore command. The value passed in should be the exact serialized data returned from
	 * {@link #dump(Object)}, since Redis uses a non-standard serialization mechanism.
	 *
	 * @param key The key to restore
	 * @param value The value to restore, as returned by {@link #dump(Object)}
	 * @param timeToLive An expiration for the restored key, or 0 for no expiration
	 * @param unit The time unit for timeToLive
	 * @param replace use {@literal true} to replace a potentially existing value instead of erroring.
	 * @throws RedisSystemException if the key you are attempting to restore already exists and {@code replace} is set to
	 *           {@literal false}.
	 */
	@Override
	public void restore(K key, byte[] value, long timeToLive, TimeUnit unit, boolean replace) {

		byte[] rawKey = rawKey(key);
		long rawTimeout = TimeoutUtils.toMillis(timeToLive, unit);

		doWithKeys(connection -> {
			connection.restore(rawKey, rawTimeout, value, replace);
			return null;
		});
	}

	@Nullable
	private <T> T doWithKeys(Function<RedisKeyCommands, T> action) {
		return execute((RedisCallback<? extends T>) connection -> action.apply(connection.keyCommands()), true);
	}

	// -------------------------------------------------------------------------
	// Methods dealing with sorting
	// -------------------------------------------------------------------------

	@Override
	@SuppressWarnings("unchecked")
	public List<V> sort(SortQuery<K> query) {
		return sort(query, valueSerializer);
	}

	@Override
	public <T> List<T> sort(SortQuery<K> query, @Nullable RedisSerializer<T> resultSerializer) {

		byte[] rawKey = rawKey(query.getKey());
		SortParameters params = QueryUtils.convertQuery(query, stringSerializer);

		List<byte[]> vals = doWithKeys(connection -> connection.sort(rawKey, params));

		return SerializationUtils.deserialize(vals, resultSerializer);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> List<T> sort(SortQuery<K> query, BulkMapper<T, V> bulkMapper) {
		return sort(query, bulkMapper, valueSerializer);
	}

	@Override
	public <T, S> List<T> sort(SortQuery<K> query, BulkMapper<T, S> bulkMapper,
			@Nullable RedisSerializer<S> resultSerializer) {

		List<S> values = sort(query, resultSerializer);

		if (values == null || values.isEmpty()) {
			return Collections.emptyList();
		}

		int bulkSize = query.getGetPattern().size();
		List<T> result = new ArrayList<>(values.size() / bulkSize + 1);

		List<S> bulk = new ArrayList<>(bulkSize);
		for (S s : values) {

			bulk.add(s);
			if (bulk.size() == bulkSize) {
				result.add(bulkMapper.mapBulk(Collections.unmodifiableList(bulk)));
				// create a new list (we could reuse the old one but the client might hang on to it for some reason)
				bulk = new ArrayList<>(bulkSize);
			}
		}

		return result;
	}

	@Override
	public Long sort(SortQuery<K> query, K storeKey) {

		byte[] rawStoreKey = rawKey(storeKey);
		byte[] rawKey = rawKey(query.getKey());
		SortParameters params = QueryUtils.convertQuery(query, stringSerializer);

		return doWithKeys(connection -> connection.sort(rawKey, params, rawStoreKey));
	}

	// -------------------------------------------------------------------------
	// Methods dealing with Transactions
	// -------------------------------------------------------------------------

	@Override
	public void watch(K key) {

		byte[] rawKey = rawKey(key);

		executeWithoutResult(connection -> connection.watch(rawKey));
	}

	@Override
	public void watch(Collection<K> keys) {

		byte[][] rawKeys = rawKeys(keys);

		executeWithoutResult(connection -> connection.watch(rawKeys));
	}

	@Override
	public void unwatch() {

		executeWithoutResult(RedisTxCommands::unwatch);
	}

	@Override
	public void multi() {
		executeWithoutResult(RedisTxCommands::multi);
	}

	@Override
	public void discard() {
		executeWithoutResult(RedisTxCommands::discard);
	}

	/**
	 * Execute a transaction, using the default {@link RedisSerializer}s to deserialize any results that are byte[]s or
	 * Collections or Maps of byte[]s or Tuples. Other result types (Long, Boolean, etc) are left as-is in the converted
	 * results. If conversion of tx results has been disabled in the {@link RedisConnectionFactory}, the results of exec
	 * will be returned without deserialization. This check is mostly for backwards compatibility with 1.0.
	 *
	 * @return The (possibly deserialized) results of transaction exec
	 */
	@Override
	public List<Object> exec() {

		List<Object> results = execRaw();
		if (getRequiredConnectionFactory().getConvertPipelineAndTxResults()) {
			return deserializeMixedResults(results, valueSerializer, hashKeySerializer, hashValueSerializer);
		} else {
			return results;
		}
	}

	@Override
	public List<Object> exec(RedisSerializer<?> valueSerializer) {
		return deserializeMixedResults(execRaw(), valueSerializer, valueSerializer, valueSerializer);
	}

	protected List<Object> execRaw() {

		List<Object> raw = execute(RedisTxCommands::exec);
		return raw == null ? Collections.emptyList() : raw;
	}

	// -------------------------------------------------------------------------
	// Methods dealing with Redis Server Commands
	// -------------------------------------------------------------------------

	@Override
	public List<RedisClientInfo> getClientList() {
		return execute(RedisServerCommands::getClientList);
	}

	@Override
	public void killClient(String host, int port) {
		executeWithoutResult(connection -> connection.killClient(host, port));
	}

	/*
	 * @see org.springframework.data.redis.core.RedisOperations#replicaOf(java.lang.String, int)
	 */
	@Override
	public void replicaOf(String host, int port) {
		executeWithoutResult(connection -> connection.replicaOf(host, port));
	}

	@Override
	public void replicaOfNoOne() {
		executeWithoutResult(RedisServerCommands::replicaOfNoOne);
	}

	@Override
	public Long convertAndSend(String channel, Object message) {

		Assert.hasText(channel, "Destination channel must not be empty");

		byte[] rawChannel = rawString(channel);
		byte[] rawMessage = rawValue(message);

		return execute(connection -> connection.publish(rawChannel, rawMessage), true);
	}

	private void executeWithoutResult(Consumer<RedisConnection> action) {
		execute(it -> {

			action.accept(it);
			return null;
		}, true);
	}

	// -------------------------------------------------------------------------
	// Methods to obtain specific operations interface objects.
	// -------------------------------------------------------------------------

	@Override
	public ClusterOperations<K, V> opsForCluster() {
		return clusterOps;
	}

	@Override
	public GeoOperations<K, V> opsForGeo() {
		return geoOps;
	}

	@Override
	public BoundGeoOperations<K, V> boundGeoOps(K key) {
		return boundOperations.createProxy(BoundGeoOperations.class, key, DataType.ZSET, this, RedisOperations::opsForGeo);
	}

	@Override
	public <HK, HV> BoundHashOperations<K, HK, HV> boundHashOps(K key) {
		return boundOperations.createProxy(BoundHashOperations.class, key, DataType.HASH, this, it -> it.opsForHash());
	}

	@Override
	public <HK, HV> HashOperations<K, HK, HV> opsForHash() {
		return new DefaultHashOperations<>(this);
	}

	@Override
	public HyperLogLogOperations<K, V> opsForHyperLogLog() {
		return hllOps;
	}

	@Override
	public ListOperations<K, V> opsForList() {
		return listOps;
	}

	@Override
	public BoundListOperations<K, V> boundListOps(K key) {
		return boundOperations.createProxy(BoundListOperations.class, key, DataType.LIST, this,
				RedisOperations::opsForList);
	}

	@Override
	public BoundSetOperations<K, V> boundSetOps(K key) {
		return boundOperations.createProxy(BoundSetOperations.class, key, DataType.SET, this, RedisOperations::opsForSet);
	}

	@Override
	public SetOperations<K, V> opsForSet() {
		return setOps;
	}

	@Override
	public <HK, HV> StreamOperations<K, HK, HV> opsForStream() {
		return (StreamOperations<K, HK, HV>) streamOps;
	}

	@Override
	public <HK, HV> StreamOperations<K, HK, HV> opsForStream(HashMapper<? super K, ? super HK, ? super HV> hashMapper) {
		return new DefaultStreamOperations<>(this, hashMapper);
	}

	@Override
	public <HK, HV> BoundStreamOperations<K, HK, HV> boundStreamOps(K key) {
		return boundOperations.createProxy(BoundStreamOperations.class, key, DataType.STREAM, this, it -> opsForStream());
	}

	@Override
	public BoundValueOperations<K, V> boundValueOps(K key) {
		return boundOperations.createProxy(BoundValueOperations.class, key, DataType.STRING, this,
				RedisOperations::opsForValue);
	}

	@Override
	public ValueOperations<K, V> opsForValue() {
		return valueOps;
	}

	@Override
	public BoundZSetOperations<K, V> boundZSetOps(K key) {
		return boundOperations.createProxy(BoundZSetOperations.class, key, DataType.ZSET, this,
				RedisOperations::opsForZSet);
	}

	@Override
	public ZSetOperations<K, V> opsForZSet() {
		return zSetOps;
	}

	@SuppressWarnings("unchecked")
	private byte[] rawKey(Object key) {
		Assert.notNull(key, "non null key required");
		if (keySerializer == null && key instanceof byte[]) {
			return (byte[]) key;
		}
		return keySerializer.serialize(key);
	}

	private byte[] rawString(String key) {
		return stringSerializer.serialize(key);
	}

	@SuppressWarnings("unchecked")
	private byte[] rawValue(Object value) {
		if (valueSerializer == null && value instanceof byte[]) {
			return (byte[]) value;
		}
		return valueSerializer.serialize(value);
	}

	private byte[][] rawKeys(Collection<K> keys) {

		byte[][] rawKeys = new byte[keys.size()][];

		int i = 0;
		for (K key : keys) {
			rawKeys[i++] = rawKey(key);
		}

		return rawKeys;
	}

	@SuppressWarnings("unchecked")
	private K deserializeKey(byte[] value) {
		return keySerializer != null ? (K) keySerializer.deserialize(value) : (K) value;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Nullable
	private List<Object> deserializeMixedResults(@Nullable List<Object> rawValues,
			@Nullable RedisSerializer valueSerializer, @Nullable RedisSerializer hashKeySerializer,
			@Nullable RedisSerializer hashValueSerializer) {

		if (rawValues == null) {
			return null;
		}

		List<Object> values = new ArrayList<>();
		for (Object rawValue : rawValues) {

			if (rawValue instanceof byte[] && valueSerializer != null) {
				values.add(valueSerializer.deserialize((byte[]) rawValue));
			} else if (rawValue instanceof List) {
				// Lists are the only potential Collections of mixed values....
				values.add(deserializeMixedResults((List) rawValue, valueSerializer, hashKeySerializer, hashValueSerializer));
			} else if (rawValue instanceof Set && !(((Set) rawValue).isEmpty())) {
				values.add(deserializeSet((Set) rawValue, valueSerializer));
			} else if (rawValue instanceof Map && !(((Map) rawValue).isEmpty())
					&& ((Map) rawValue).values().iterator().next() instanceof byte[]) {
				values.add(SerializationUtils.deserialize((Map) rawValue, hashKeySerializer, hashValueSerializer));
			} else {
				values.add(rawValue);
			}
		}

		return values;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Set<?> deserializeSet(Set rawSet, @Nullable RedisSerializer valueSerializer) {

		if (rawSet.isEmpty()) {
			return rawSet;
		}

		Object setValue = rawSet.iterator().next();

		if (setValue instanceof byte[] && valueSerializer != null) {
			return (SerializationUtils.deserialize(rawSet, valueSerializer));
		} else if (setValue instanceof Tuple) {
			return convertTupleValues(rawSet, valueSerializer);
		} else {
			return rawSet;
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Set<TypedTuple<V>> convertTupleValues(Set<Tuple> rawValues, @Nullable RedisSerializer valueSerializer) {

		Set<TypedTuple<V>> set = new LinkedHashSet<>(rawValues.size());
		for (Tuple rawValue : rawValues) {
			Object value = rawValue.getValue();
			if (valueSerializer != null) {
				value = valueSerializer.deserialize(rawValue.getValue());
			}
			set.add(new DefaultTypedTuple(value, rawValue.getScore()));
		}
		return set;
	}

}
