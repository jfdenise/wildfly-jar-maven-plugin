# Web Console WildFly bootable jar example

Provision a server with jaxrs and web console. A CLI script adds the user admin/admin in order to have access to the web console.

* To build: mvn package
* To run: mvn wildfly-jar:run
* Access the Web Console: http://127.0.0.1:9990 (user admin, password admin)
* Access the application: http://127.0.0.1:8080/hello

