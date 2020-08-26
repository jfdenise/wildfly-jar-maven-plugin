# Web session sharing between multiple bootable JAR instances example

* To build: mvn package
* To run first instance: java -jar target/web-clustering-bootable.jar -Djboss.socket.binding.port-offset=10 -Djboss.node.name=node1
* To run second instance: java -jar target/web-clustering-bootable.jar -Djboss.node.name=node2
* Access the application running in the first instance: http://127.0.0.1:8080
* Note sessionID and user creation time.
* Kill first instance
* Access the application running in the second instance: http://127.0.0.1:8090
* SessionID and user creation time should be the same as you previously noted.

Openshift binary build and deployment (Kubernetes Ping)
=======================================================

Steps:
* mvn package -Popenshift
* mkdir os && cp target/web-clustering-bootable.jar os/
* oc policy add-role-to-user view system:serviceaccount:$(oc project -q):default
* oc new-build --strategy source --binary --image-stream openjdk11 --name web-clustering
* oc start-build web-clustering --from-dir ./os/
* oc new-app web-clustering -e KUBERNETES_NAMESPACE=myproject -e JGROUPS_CLUSTER_PASSWORD=mypassword
 * KUBERNETES_NAMESPACE env variable is required to see other pods in the project, otherwise the server attempts to retrieve pods from the 'default' namespace that is not the one our project is using.
 * JGROUPS_CLUSTER_PASSWORD env variable is expected by the server in order to establish authenticated communication between servers. 
* oc expose svc/web-clustering
* Access the application route, note the user created time and session ID.
* Scale the application to 2 pods: oc scale --replicas=2 deployment web-clustering
* List pods: oc get pods
* Kill the oldest POD (that answered the first application request): oc delete pod web-clustering-1-r4cx8 -n myproject
* Access the application again, you will notice that the displayed values are the same, web session has been shared between the 2 pods.

Openshift binary build and deployment (DNS Ping)
================================================
Steps:
* mvn package -Popenshift-dns-ping
* mkdir os && cp target/web-clustering-bootable.jar os/
* oc new-build --strategy source --binary --image-stream openjdk11 --name web-clustering
* oc start-build web-clustering --from-dir ./os/
* oc create -f ping/web-clustering-ping.yml
* oc new-app web-clustering -e OPENSHIFT_DNS_PING_SERVICE_NAME=web-clustering-ping -e JGROUPS_CLUSTER_PASSWORD=mypassword
 * OPENSHIFT_DNS_PING_SERVICE_NAME env variable must be set to the ping service name (that listens on port 8888).
 * JGROUPS_CLUSTER_PASSWORD env variable is expected by the server in order to establish authenticated communication between servers. 
* oc expose svc/web-clustering
* Access the application route, note the user created time and session ID.
* Scale the application to 2 pods: oc scale --replicas=2 deployment web-clustering
* List pods: oc get pods
* Kill the oldest POD (that answered the first application request): oc delete pod web-clustering-1-r4cx8 -n myproject
* Access the application again, you will notice that the displayed values are the same, web session has been shared between the 2 pods.