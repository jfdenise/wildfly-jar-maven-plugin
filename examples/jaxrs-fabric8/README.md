# JAXRS WildFly bootable jar fabric8 example

* To build: mvn package
* To run: mvn wildfly-jar:run
* Access the application: http://127.0.0.1:8080/hello

# Build with fabric8.

You must be logged in openshift and have an accessible docker server.

NB: Health-check listen on management interface :9990/health

* mvn fabric8:deploy -Popenshift

* Then access the [openshift created route]/hello 

