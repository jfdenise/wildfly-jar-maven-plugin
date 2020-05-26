# Keycloak example

Pre-requisites
===============

Start keycloak server (should listen on localhost:8080). Configure a new client, a new _user_ role, add a user (make sure the user is in role _user_). 
Generate keycloak.json installation file, copy it to src/main/webapp/WEB-INF/keycloak.json).

NB: The application will be deployed in the root context, its redirect url is: _http://127.0.0.1:8090/_ 


Build and run the application
=============================

* Build: mvn package
* Run: java -jar target/keycloak-wildfly.jar -Djboss.socket.binding.port-offset=10
* Access the application: http://127.0.0.1:8090/
* Click on LOGIN
* Log as your user
