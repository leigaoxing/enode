package org.enodeframework.spring;

import org.enodeframework.common.serializing.SerializeService;
import org.enodeframework.configurations.EventStoreOptions;
import org.enodeframework.eventing.EventSerializer;
import org.enodeframework.jdbc.JDBCEventStore;
import org.enodeframework.jdbc.JDBCPublishedVersionStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@ConditionalOnProperty(prefix = "spring.enode", name = "eventstore", havingValue = "jdbc-pg")
public class EnodeJDBCPgEventStoreAutoConfig {

    @Bean
    public JDBCEventStore jdbcEventStore(@Qualifier("enodePgDataSource") DataSource enodePgDataSource, EventSerializer eventSerializer, SerializeService serializeService) {
        JDBCEventStore eventStore = new JDBCEventStore(enodePgDataSource, EventStoreOptions.pg(), eventSerializer, serializeService);
        return eventStore;
    }

    @Bean
    public JDBCPublishedVersionStore jdbcPublishedVersionStore(@Qualifier("enodePgDataSource") DataSource enodePgDataSource) {
        JDBCPublishedVersionStore publishedVersionStore = new JDBCPublishedVersionStore(enodePgDataSource, EventStoreOptions.pg());
        return publishedVersionStore;
    }
}
