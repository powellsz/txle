/*
 * Copyright (c) 2018-2019 ActionTech.
 * License: http://www.apache.org/licenses/LICENSE-2.0 Apache License 2.0 or higher.
 */

package org.apache.servicecomb.saga.alpha.server.restapi;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.servicecomb.saga.alpha.server.ConfigLoading;
import org.apache.servicecomb.saga.alpha.server.kafka.KafkaMessageProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigRestApi {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigRestApi.class);

    @Autowired
    private ApplicationContext ctx;

    @GetMapping("/health")
    public String consulCheckServerHealth() {
        return "ok";
    }

    @GetMapping("/reloadConfig/kafka")
    public String reloadKafkaConfig() {
        return reInjectPropertyToBean("kafkaMessageProducer", KafkaMessageProducer.class, "kafkaProducer", new KafkaProducer<>(ConfigLoading.loadKafkaProperties()));
    }

    @GetMapping("/reloadConfig/db")
    public String reloadDBConfig() {
        return "ok";
    }

    @GetMapping("/reloadConfig")
    public String reloadAllConfig() {
        reloadKafkaConfig();
//        reloadDBConfig();
        return "ok";
    }

    // To inject new value for some property to some bean again.
    private String reInjectPropertyToBean(String beanName, Class clazz, String propertyName, Object propertyValue) {
        try {
            DefaultListableBeanFactory defaultListableBeanFactory = (DefaultListableBeanFactory) ctx.getAutowireCapableBeanFactory();
            BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(clazz);
            beanDefinitionBuilder.addPropertyValue(propertyName, propertyValue);
            defaultListableBeanFactory.registerBeanDefinition(beanName, beanDefinitionBuilder.getBeanDefinition());
            return Boolean.TRUE.toString();
        } catch (Exception e) {
            LOG.error("Failed to execute method 'reInjectPropertyToBean', beanName - " + beanName + ".", e);
        }
        return Boolean.FALSE.toString();
    }

}
