/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.wildfly.plugins.demo.jaxrs;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoDatabase;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;

@ApplicationScoped
public class MongoDBProducer {

    @Produces
    public MongoClient createMongo() {
        return new MongoClient(new ServerAddress(), new MongoClientOptions.Builder().build());
    }

    @Produces
    public MongoDatabase createDB(MongoClient client) {
        return client.getDatabase("testdb");
    }

    public void close(@Disposes MongoClient toClose) {
        toClose.close();
    }
}

