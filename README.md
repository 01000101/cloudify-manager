[![Build Status](https://secure.travis-ci.org/CloudifySource/cosmo-manager.png?branch=develop)](http://travis-ci.org/CloudifySource/cosmo-manager) develop branch

# Cloudify Cosmo Management #

The Cloudify Cosmo Management project is the scafolding for the deployment, monitoring and automatic managing services 
installed on the cloud.


If you want to see it in action, keep reading...

Requirements
============

- Linux/MAC operation system.
- Git
- JAVA runtime 1.7 (openjdk or oracle).
- Python2 with the following packages installed: (Use pip to install)
	- celery
	- fabric
    - vagrant
    - bernhard
- Riemann (http://riemann.io)
- RabbitMQ (http://www.rabbitmq.com/download.html)


Setup
=====

- Riemann process running:	
	- run "wget https://raw.github.com/CloudifySource/cosmo-manager/develop/orchestrator/src/test/resources/org/cloudifysource/cosmo/orchestrator/integration/config/riemann.vagrant.config"
	- include riemann in your path and run the following command : "riemann riemann.vagrant.config"		

- RabbitMQ process running:	
	- Start RabbitMQ as a service, no configuration is required.


Build
=====

- git clone https://github.com/CloudifySource/cosmo-manager.git
- cd cosmo-manager/orchestrator
- mvn -DskipTests package -Pall

Run
===

- cd target
- wget https://raw.github.com/CloudifySource/cosmo-manager/develop/orchestrator/src/test/resources/org/cloudifysource/cosmo/dsl/integration_phase3/integration-phase3.yaml
- java -jar orchestrator-0.1-SNAPSHOT-all.jar integration-phase3.yaml