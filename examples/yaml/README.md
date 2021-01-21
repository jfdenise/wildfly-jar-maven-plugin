# Yaml WildFly bootable jar example

Build a bootable JAR containing a JAX-RS resource, configure it with a yaml file.

Build and run
========

* To build: `mvn package`
* To run: ` java -jar target/yaml-bootable.jar --yaml-config=config.yml`
* Access the application: `http://127.0.0.1:8080/hello`
