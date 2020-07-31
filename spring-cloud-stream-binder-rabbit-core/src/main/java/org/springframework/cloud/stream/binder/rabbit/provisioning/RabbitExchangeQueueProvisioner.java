/*
 * Copyright 2016-2017 the original author or authors.
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

package org.springframework.cloud.stream.binder.rabbit.provisioning;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.DeclarationExceptionEvent;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.rabbit.properties.RabbitCommonProperties;
import org.springframework.cloud.stream.binder.rabbit.properties.RabbitConsumerProperties;
import org.springframework.cloud.stream.binder.rabbit.properties.RabbitProducerProperties;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.cloud.stream.provisioning.ProvisioningException;
import org.springframework.cloud.stream.provisioning.ProvisioningProvider;
import org.springframework.context.ApplicationListener;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * AMQP implementation for {@link ProvisioningProvider}
 *
 * @author Soby Chacko
 * @author Gary Russell
 */
public class RabbitExchangeQueueProvisioner implements ApplicationListener<DeclarationExceptionEvent>,
		ProvisioningProvider<ExtendedConsumerProperties<RabbitConsumerProperties>,
							ExtendedProducerProperties<RabbitProducerProperties>> {

	// 匿名队列前缀
	private static final AnonymousQueue.Base64UrlNamingStrategy ANONYMOUS_GROUP_NAME_GENERATOR = new AnonymousQueue.Base64UrlNamingStrategy("anonymous.");

	/**
	 * The delimiter between a group and index when constructing a binder
	 * consumer/producer.
	 */
	private static final String GROUP_INDEX_DELIMITER = ".";

	protected final Log logger = LogFactory.getLog(getClass());

	// RabbitMQ 客户端
	private final RabbitAdmin rabbitAdmin;

	private boolean notOurAdminException;

	private final GenericApplicationContext autoDeclareContext = new GenericApplicationContext();

	public RabbitExchangeQueueProvisioner(ConnectionFactory connectionFactory) {
		this.rabbitAdmin = new RabbitAdmin(connectionFactory);
		this.autoDeclareContext.refresh();
		this.rabbitAdmin.setApplicationContext(this.autoDeclareContext);
		this.rabbitAdmin.afterPropertiesSet();
	}

	/**
	 * 发布消息的端点
	 * @param name 端点名称
	 * @param producerProperties 配置属性
	 * @return
	 */
	@Override
	public ProducerDestination provisionProducerDestination(String name, ExtendedProducerProperties<RabbitProducerProperties> producerProperties) {
		// 构建Exchange
		final String exchangeName = applyPrefix(producerProperties.getExtension().getPrefix(), name);
		Exchange exchange = buildExchange(producerProperties.getExtension(), exchangeName);
		if (producerProperties.getExtension().isDeclareExchange()) {
			// 创建Exchange
			declareExchange(exchangeName, exchange);
		}
		Binding binding = null;
		for (String requiredGroupName : producerProperties.getRequiredGroups()) {
			// 组装 QueueName
			String baseQueueName = producerProperties.getExtension().isQueueNameGroupOnly() ? requiredGroupName : (exchangeName + "." + requiredGroupName);
			if (!producerProperties.isPartitioned()) {
				// 分区处理
				Queue queue = new Queue(baseQueueName, true, false, false,
						queueArgs(baseQueueName, producerProperties.getExtension(), false));
				// 创建 Queue
				declareQueue(baseQueueName, queue);
				// 绑定死信队列
				autoBindDLQ(baseQueueName, baseQueueName, producerProperties.getExtension());
				if (producerProperties.getExtension().isBindQueue()) {
					binding = notPartitionedBinding(exchange, queue, producerProperties.getExtension());
				}
			}
			else {
				// 分区处理
				// if the stream is partitioned, create one queue for each target partition for the default group
				for (int i = 0; i < producerProperties.getPartitionCount(); i++) {
					String partitionSuffix = "-" + i;
					String partitionQueueName = baseQueueName + partitionSuffix;
					Queue queue = new Queue(partitionQueueName, true, false, false,
							queueArgs(partitionQueueName, producerProperties.getExtension(), false));
					declareQueue(queue.getName(), queue);
					autoBindDLQ(baseQueueName, baseQueueName + partitionSuffix, producerProperties.getExtension());
					if (producerProperties.getExtension().isBindQueue()) {
						String prefix = producerProperties.getExtension().getPrefix();
						String destination = StringUtils.isEmpty(prefix) ? exchangeName : exchangeName.substring(prefix.length());
						binding = partitionedBinding(destination, exchange, queue, producerProperties.getExtension(), i);
					}
				}
			}
		}
		return new RabbitProducerDestination(exchange, binding);
	}

	@Override
	public ConsumerDestination provisionConsumerDestination(String name, String group, ExtendedConsumerProperties<RabbitConsumerProperties> properties) {
		boolean anonymous = !StringUtils.hasText(group);
		// 构建QueueName
		String  baseQueueName = anonymous ? groupedName(name, ANONYMOUS_GROUP_NAME_GENERATOR.generateName())
					: properties.getExtension().isQueueNameGroupOnly() ? group : groupedName(name, group);
		if (this.logger.isInfoEnabled()) {
			this.logger.info("declaring queue for inbound: " + baseQueueName + ", bound to: " + name);
		}
		String prefix = properties.getExtension().getPrefix();
		// 构建ExchangeName
		final String exchangeName = applyPrefix(prefix, name);
		Exchange exchange = buildExchange(properties.getExtension(), exchangeName);
		if (properties.getExtension().isDeclareExchange()) {
			// 创建Exchange
			declareExchange(exchangeName, exchange);
		}
		String queueName = applyPrefix(prefix, baseQueueName);
		boolean partitioned = !anonymous && properties.isPartitioned();
		boolean durable = !anonymous && properties.getExtension().isDurableSubscription();
		Queue queue;
		if (anonymous) {
			queue = new Queue(queueName, false, true, true, queueArgs(queueName, properties.getExtension(), false));
		}
		else {
			if (partitioned) {
				String partitionSuffix = "-" + properties.getInstanceIndex();
				queueName += partitionSuffix;
			}
			if (durable) {
				queue = new Queue(queueName, true, false, false,
						queueArgs(queueName, properties.getExtension(), false));
			}
			else {
				queue = new Queue(queueName, false, false, true,
						queueArgs(queueName, properties.getExtension(), false));
			}
		}
		// 创建队列
		declareQueue(queueName, queue);
		Binding binding = null;
		if (properties.getExtension().isBindQueue()) {
			// 将队列和交换器绑定
			binding = declareConsumerBindings(name, properties, exchange, partitioned, queue);
		}
		if (durable) {
			// 绑定死信队列
			autoBindDLQ(applyPrefix(properties.getExtension().getPrefix(), baseQueueName), queueName,
					properties.getExtension());
		}
		return new RabbitConsumerDestination(queue, binding);
	}

	/**
	 * Construct a name comprised of the name and group.
	 *
	 * @param name  the name.
	 * @param group the group.
	 * @return the constructed name.
	 */
	protected final String groupedName(String name, String group) {
		return name + GROUP_INDEX_DELIMITER + (StringUtils.hasText(group) ? group : "default");
	}

	private Binding partitionedBinding(String destination, Exchange exchange, Queue queue,
									RabbitCommonProperties extendedProperties, int index) {
		String bindingKey = extendedProperties.getBindingRoutingKey();
		if (bindingKey == null) {
			bindingKey = destination;
		}
		bindingKey += "-" + index;
		if (exchange instanceof TopicExchange) {
			Binding binding = BindingBuilder.bind(queue)
					.to((TopicExchange) exchange)
					.with(bindingKey);
			declareBinding(queue.getName(), binding);
			return binding;
		}
		else if (exchange instanceof DirectExchange) {
			Binding binding = BindingBuilder.bind(queue)
					.to((DirectExchange) exchange)
					.with(bindingKey);
			declareBinding(queue.getName(), binding);
			return binding;
		}
		else if (exchange instanceof FanoutExchange) {
			throw new ProvisioningException("A fanout exchange is not appropriate for partitioned apps");
		}
		else {
			throw new ProvisioningException("Cannot bind to a " + exchange.getType() + " exchange");
		}
	}

	private Binding declareConsumerBindings(String name, ExtendedConsumerProperties<RabbitConsumerProperties> properties,
										Exchange exchange, boolean partitioned, Queue queue) {
		if (partitioned) {
			return partitionedBinding(name, exchange, queue, properties.getExtension(), properties.getInstanceIndex());
		}
		else {
			return notPartitionedBinding(exchange, queue, properties.getExtension());
		}
	}

	private Binding notPartitionedBinding(Exchange exchange, Queue queue, RabbitCommonProperties extendedProperties) {

		String routingKey = extendedProperties.getBindingRoutingKey();
		if (routingKey == null) {
			routingKey = "#";
		}
		if (exchange instanceof TopicExchange) {
			// 将队列绑定到交换器
			Binding binding = BindingBuilder.bind(queue).to((TopicExchange) exchange).with(routingKey);
			declareBinding(queue.getName(), binding);
			return binding;
		}
		else if (exchange instanceof DirectExchange) {
			Binding binding = BindingBuilder.bind(queue)
					.to((DirectExchange) exchange)
					.with(routingKey);
			declareBinding(queue.getName(), binding);
			return binding;
		}
		else if (exchange instanceof FanoutExchange) {
			Binding binding = BindingBuilder.bind(queue)
					.to((FanoutExchange) exchange);
			declareBinding(queue.getName(), binding);
			return binding;
		}
		else {
			throw new ProvisioningException("Cannot bind to a " + exchange.getType() + " exchange");
		}
	}

	/**
	 * If so requested, declare the DLX/DLQ and bind it. The DLQ is bound to the DLX with a routing key of the original
	 * queue name because we use default exchange routing by queue name for the original message.
	 * @param baseQueueName   The base name for the queue (including the binder prefix, if any).
	 * @param routingKey  The routing key for the queue.
	 * @param properties the properties.
	 */
	private void autoBindDLQ(final String baseQueueName, String routingKey, RabbitCommonProperties properties) {
		boolean autoBindDlq = properties.isAutoBindDlq();
		if (this.logger.isDebugEnabled()) {
			this.logger.debug("autoBindDLQ=" + autoBindDlq
					+ " for: " + baseQueueName);
		}
		// 1. 绑定死信队列
		if (autoBindDlq) {
			String dlqName;
			if (properties.getDeadLetterQueueName() == null) {
				dlqName = constructDLQName(baseQueueName);
			}
			else {
				dlqName = properties.getDeadLetterQueueName();
			}
			Queue dlq = new Queue(dlqName, true, false, false, queueArgs(dlqName, properties, true));
			// 1.1 创建死信队列
			declareQueue(dlqName, dlq);
			String dlxName = deadLetterExchangeName(properties);
			final DirectExchange dlx = new DirectExchange(dlxName);
			// 1.2 创建死信交换器
			declareExchange(dlxName, dlx);
			BindingBuilder.DirectExchangeRoutingKeyConfigurer bindingBuilder = BindingBuilder.bind(dlq).to(dlx);
			Binding dlqBinding;
			if (properties.getDeadLetterRoutingKey() == null) {
				dlqBinding = bindingBuilder.with(routingKey);
			}
			else {
				dlqBinding = bindingBuilder.with(properties.getDeadLetterRoutingKey());
			}
			// 1.3 将死信队列绑定到死信交换器
			declareBinding(dlqName, dlqBinding);
			if (properties instanceof RabbitConsumerProperties && ((RabbitConsumerProperties) properties).isRepublishToDlq()) {
				/*
				 *  Also bind with the base queue name when republishToDlq is used, which does not know about
				 * partitioning
				 */
				declareBinding(dlqName, BindingBuilder.bind(dlq).to(dlx).with(baseQueueName));
			}
		}
	}

	/**
	 * For binder implementations that support dead lettering, construct the name of the
	 * dead letter entity for the underlying pipe name.
	 *
	 * @param name the name.
	 */
	public static String constructDLQName(String name) {
		return name + ".dlq";
	}


	private String deadLetterExchangeName(RabbitCommonProperties properties) {
		if (properties.getDeadLetterExchange() == null) {
			return properties.getPrefix() + RabbitCommonProperties.DEAD_LETTER_EXCHANGE;
		}
		else {
			return properties.getDeadLetterExchange();
		}
	}


	private void declareQueue(String beanName, Queue queue) {
		try {
			this.rabbitAdmin.declareQueue(queue);
		}
		catch (AmqpConnectException e) {
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Declaration of queue: " + queue.getName() + " deferred - connection not available");
			}
		}
		catch (RuntimeException e) {
			if (this.notOurAdminException) {
				this.notOurAdminException = false;
				throw e;
			}
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Declaration of queue: " + queue.getName() + " deferred", e);
			}
		}
		addToAutoDeclareContext(beanName, queue);
	}

	private Map<String, Object> queueArgs(String queueName, RabbitCommonProperties properties, boolean isDlq) {
		Map<String, Object> args = new HashMap<>();
		if (!isDlq) {
			if (properties.isAutoBindDlq()) {
				String dlx;
				if (properties.getDeadLetterExchange() != null) {
					dlx = properties.getDeadLetterExchange();
				}
				else {
					dlx = applyPrefix(properties.getPrefix(), "DLX");
				}
				args.put("x-dead-letter-exchange", dlx);
				String dlRk;
				if (properties.getDeadLetterRoutingKey() != null) {
					dlRk = properties.getDeadLetterRoutingKey();
				}
				else {
					dlRk = queueName;
				}
				args.put("x-dead-letter-routing-key", dlRk);
			}
			additionalArgs(args, properties.getExpires(), properties.getMaxLength(), properties.getMaxLengthBytes(),
					properties.getMaxPriority(), properties.getTtl(), properties.isLazy());
		}
		else {
			if (properties.getDlqDeadLetterExchange() != null) {
				args.put("x-dead-letter-exchange", properties.getDlqDeadLetterExchange());
			}
			if (properties.getDlqDeadLetterRoutingKey() != null) {
				args.put("x-dead-letter-routing-key", properties.getDlqDeadLetterRoutingKey());
			}
			additionalArgs(args, properties.getDlqExpires(), properties.getDlqMaxLength(),
					properties.getDlqMaxLengthBytes(), properties.getDlqMaxPriority(), properties.getDlqTtl(),
					properties.isDlqLazy());
		}
		return args;
	}

	private void additionalArgs(Map<String, Object> args, Integer expires, Integer maxLength, Integer maxLengthBytes,
								Integer maxPriority, Integer ttl, boolean lazy) {
		if (expires != null) {
			args.put("x-expires", expires);
		}
		if (maxLength != null) {
			args.put("x-max-length", maxLength);
		}
		if (maxLengthBytes != null) {
			args.put("x-max-length-bytes", maxLengthBytes);
		}
		if (maxPriority != null) {
			args.put("x-max-priority", maxPriority);
		}
		if (ttl != null) {
			args.put("x-message-ttl", ttl);
		}
		if (lazy) {
			args.put("x-queue-mode", "lazy");
		}
	}


	public static String applyPrefix(String prefix, String name) {
		return prefix + name;
	}

	private Exchange buildExchange(RabbitCommonProperties properties, String exchangeName) {
		try {
			ExchangeBuilder builder = new ExchangeBuilder(exchangeName, properties.getExchangeType());
			builder.durable(properties.isExchangeDurable());
			if (properties.isExchangeAutoDelete()) {
				builder.autoDelete();
			}
			if (properties.isDelayedExchange()) {
				builder.delayed();
			}
			return builder.build();
		}
		catch (Exception e) {
			throw new ProvisioningException("Failed to create exchange object", e);
		}
	}

	private void declareExchange(final String rootName, final Exchange exchange) {
		try {
			this.rabbitAdmin.declareExchange(exchange);
		}
		catch (AmqpConnectException e) {
			if (this.logger.isDebugEnabled()) {
				this.logger.debug(
						"Declaration of exchange: " + exchange.getName() + " deferred - connection not available");
			}
		}
		catch (RuntimeException e) {
			if (this.notOurAdminException) {
				this.notOurAdminException = false;
				throw e;
			}
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Declaration of exchange: " + exchange.getName() + " deferred", e);
			}
		}
		addToAutoDeclareContext(rootName + ".exchange", exchange);
	}

	private void addToAutoDeclareContext(String name, Object bean) {
		synchronized (this.autoDeclareContext) {
			if (!this.autoDeclareContext.containsBean(name)) {
				this.autoDeclareContext.getBeanFactory().registerSingleton(name, bean);
			}
		}
	}

	private void declareBinding(String rootName, org.springframework.amqp.core.Binding binding) {
		try {
			this.rabbitAdmin.declareBinding(binding);
		}
		catch (AmqpConnectException e) {
			if (this.logger.isDebugEnabled()) {
				this.logger.debug(
						"Declaration of binding: " + rootName + ".binding deferred - connection not available");
			}
		}
		catch (RuntimeException e) {
			if (this.notOurAdminException) {
				this.notOurAdminException = false;
				throw e;
			}
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Declaration of binding: " + rootName + ".binding deferred", e);
			}
		}
		addToAutoDeclareContext(rootName + ".binding", binding);
	}

	public void cleanAutoDeclareContext(String name) {
		synchronized (this.autoDeclareContext) {
			removeSingleton(name + ".binding");
			removeSingleton(name);
			String dlq = name + ".dlq";
			removeSingleton(dlq + ".binding");
			removeSingleton(dlq);
		}
	}

	private void removeSingleton(String name) {
		if (this.autoDeclareContext.containsBean(name)) {
			ConfigurableListableBeanFactory beanFactory = this.autoDeclareContext.getBeanFactory();
			if (beanFactory instanceof DefaultListableBeanFactory) {
				((DefaultListableBeanFactory) beanFactory).destroySingleton(name);
			}
		}
	}

	@Override
	public void onApplicationEvent(DeclarationExceptionEvent event) {
		this.notOurAdminException = true; // our admin doesn't have an event publisher
	}

	// 生产者端点
	private static final class RabbitProducerDestination implements ProducerDestination {
		// 交换器
		private final Exchange exchange;
		// 队列和Exchange的绑定
		private final Binding binding;

		RabbitProducerDestination(Exchange exchange, Binding binding) {
			Assert.notNull(exchange, "exchange must not be null");
			this.exchange = exchange;
			this.binding = binding;
		}

		@Override
		public String getName() {
			return this.exchange.getName();
		}

		@Override
		public String getNameForPartition(int partition) {
			return this.exchange.getName();
		}

		@Override
		public String toString() {
			return "RabbitProducerDestination{" +
					"exchange=" + exchange +
					", binding=" + binding +
					'}';
		}
	}

	private static final class RabbitConsumerDestination implements ConsumerDestination {
		// 队列
		private final Queue queue;
		// 队列和Exchange的绑定
		private final Binding binding;

		RabbitConsumerDestination(Queue queue, Binding binding) {
			Assert.notNull(queue, "queue must not be null");
			this.queue = queue;
			this.binding = binding;
		}

		@Override
		public String toString() {
			return "RabbitConsumerDestination{" +
					"queue=" + queue +
					", binding=" + binding +
					'}';
		}

		@Override
		public String getName() {
			return this.queue.getName();
		}
	}

}
