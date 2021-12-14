# JAX-RS WildFly bootable jar example

For yaml: java -jar target/jaxrs-bootable.jar --yaml=simple.yaml

Build a bootable JAR containing a JAX-RS resource.

Build and run
=============

* To build: `mvn package`
* To run: `mvn wildfly-jar:run`
* Access the application: `http://127.0.0.1:8080/hello`
