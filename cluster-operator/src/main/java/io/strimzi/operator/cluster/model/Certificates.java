/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Secret;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.certs.CertAndKey;
import io.strimzi.certs.CertManager;
import io.strimzi.certs.Subject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class Certificates {

    protected static final Logger log = LogManager.getLogger(Certificates.class);

    // the Kubernetes service DNS domain is customizable on cluster creation but it's "cluster.local" by default
    // there is no clean way to get it from a running application so we are passing it through an env var
    public static final String KUBERNETES_SERVICE_DNS_DOMAIN =
            System.getenv().getOrDefault("KUBERNETES_SERVICE_DNS_DOMAIN", "cluster.local");

    private final CertManager certManager;
    private final CertAndKey clusterCa;
    private CertAndKey clientsCa;
    private final Secret brokersSecret;
    private CertAndKey eoCertAndKey;
    private CertAndKey toCertAndKey;
    private final Secret zkNodesSecret;

    public Certificates(CertManager certManager, String clusterName, List<Secret> secrets) {
        this.certManager = certManager;
        Secret clusterCa = null;
        Secret clientsCa = null;
        Secret brokersSecret = null;
        Secret eoSecrets = null;
        Secret toSecret = null;
        Secret zkNodesSecret = null;
        for (Secret secret: secrets) {
            String name = secret.getMetadata().getName();
            if (AbstractModel.getClusterCaName(clusterName).equals(name)) {
                clusterCa = secret;
            } else if (KafkaCluster.clientsCASecretName(clusterName).equals(name)) {
                clientsCa = secret;
            } else if (KafkaCluster.brokersSecretName(clusterName).equals(name)) {
                brokersSecret = secret;
            } else if (EntityOperator.secretName(clusterName).equals(name)) {
                eoSecrets = secret;
            } else if (TopicOperator.secretName(clusterName).equals(name)) {
                toSecret = secret;
            } else if (ZookeeperCluster.nodesSecretName(clusterName).equals(name)) {
                zkNodesSecret = secret;
            }
        }
        if (clusterCa == null) {
            throw new NoCertificateSecretException("The cluster CA certificate Secret is missing");
        } else if (clusterCa.getData().get("cluster-ca.key") == null) {
            throw new NoCertificateSecretException("The cluster CA certificate Secret is missing data.cluster-ca.key");
        } else if (clusterCa.getData().get("cluster-ca.crt") == null) {
            throw new NoCertificateSecretException("The cluster CA certificate Secret is missing data.cluster-ca.crt");
        }
        this.clusterCa = new CertAndKey(
                decodeFromSecret(clusterCa, "cluster-ca.key"),
                decodeFromSecret(clusterCa, "cluster-ca.crt"));
        this.clientsCa = clientsCa == null ? null : new CertAndKey(
                decodeFromSecret(clientsCa, "clients-ca.key"),
                decodeFromSecret(clientsCa, "clients-ca.crt"));
        this.brokersSecret = brokersSecret;
        this.eoCertAndKey = eoSecrets == null ? null : new CertAndKey(
                decodeFromSecret(eoSecrets, "entity-operator.key"),
                decodeFromSecret(eoSecrets, "entity-operator.crt"));
        this.toCertAndKey = toSecret == null ? null : new CertAndKey(
                decodeFromSecret(toSecret, "entity-operator.key"),
                decodeFromSecret(toSecret, "entity-operator.crt"));
        this.zkNodesSecret = zkNodesSecret;
    }

    private static byte[] decodeFromSecret(Secret secret, String key) {
        return Base64.getDecoder().decode(secret.getData().get(key));
    }

    public CertAndKey clusterCa() {
        return clusterCa;
    }

    public CertAndKey clientsCa(Kafka kafka) throws IOException {
        if (clientsCa == null) {
            clientsCa = generateClientsCa(kafka);
        }
        return clientsCa;
    }

    public CertAndKey toCert(Kafka kafka) throws IOException {
        if (toCertAndKey == null) {
            toCertAndKey = generateToCert(kafka);
        }
        return toCertAndKey;
    }

    public CertAndKey eoCert(Kafka kafka) throws IOException {
        if (eoCertAndKey == null) {
            eoCertAndKey = generateEoCert(kafka);
        }
        return eoCertAndKey;
    }

    private CertAndKey generateClientsCa(Kafka kafka) throws IOException {
        log.debug("Clients CA to generate");
        File clientsCAkeyFile = File.createTempFile("tls", "clients-ca-key");
        try {
            File clientsCAcertFile = File.createTempFile("tls", "clients-ca-cert");
            try {

                Subject sbj = new Subject();
                sbj.setOrganizationName("io.strimzi");
                sbj.setCommonName("kafka-clients-ca");

                certManager.generateSelfSignedCert(clientsCAkeyFile, clientsCAcertFile, sbj, ModelUtils.getCertificateValidity(kafka));
                return new CertAndKey(Files.readAllBytes(clientsCAkeyFile.toPath()), Files.readAllBytes(clientsCAcertFile.toPath()));
            } finally {
                if (!clientsCAcertFile.delete()) {
                    log.warn("{} cannot be deleted", clientsCAcertFile.getName());
                }
            }
        } finally {
            if (!clientsCAkeyFile.delete()) {
                log.warn("{} cannot be deleted", clientsCAkeyFile.getName());
            }
        }

    }

    private CertAndKey generateClusterSignedCert(Kafka kafka, String commonName) throws IOException {
        File csrFile = File.createTempFile("tls", "csr");
        File keyFile = File.createTempFile("tls", "key");
        File certFile = File.createTempFile("tls", "cert");

        Subject sbj = new Subject();
        sbj.setOrganizationName("io.strimzi");
        sbj.setCommonName(commonName);

        certManager.generateCsr(keyFile, csrFile, sbj);
        certManager.generateCert(csrFile, clusterCa.key(), clusterCa.cert(),
                certFile, ModelUtils.getCertificateValidity(kafka));

        return new CertAndKey(Files.readAllBytes(keyFile.toPath()), Files.readAllBytes(certFile.toPath()));
    }

    private CertAndKey generateToCert(Kafka kafka) throws IOException {
        log.debug("Topic Operator certificate to generate");
        return generateClusterSignedCert(kafka, TopicOperator.topicOperatorName(kafka.getMetadata().getName()));
    }

    private CertAndKey generateEoCert(Kafka kafka) throws IOException {
        log.debug("Entity Operator certificate to generate");
        return generateClusterSignedCert(kafka, EntityOperator.entityOperatorName(kafka.getMetadata().getName()));
    }

    /**
     * Copy already existing certificates from provided Secret based on number of effective replicas
     * and maybe generate new ones for new replicas (i.e. scale-up)
     */
    protected Map<String, CertAndKey> maybeCopyOrGenerateCerts(int replicas,
                                                               String name,
                                                               Function<Integer, Map<String, String>> sanFn,
                                                               Kafka kafka,
                                                               Secret secret,
                                                               Function<Integer, String> podNameFn) throws IOException {
        int replicasInSecret = secret == null ? 0 : (secret.getData().size() - 1) / 2;
        Map<String, CertAndKey> certs = new HashMap<>();
        // copying the minimum number of certificates already existing in the secret
        // scale up -> it will copy all certificates
        // scale down -> it will copy just the requested number of replicas
        for (int i = 0; i < Math.min(replicasInSecret, replicas); i++) {

            String podName = podNameFn.apply(i);
            log.debug("{} already exists", podName);
            certs.put(
                    podName,
                    new CertAndKey(
                            decodeFromSecret(secret, podName + ".key"),
                            decodeFromSecret(secret, podName + ".crt")));
        }

        File brokerCsrFile = File.createTempFile("tls", "broker-csr");
        File brokerKeyFile = File.createTempFile("tls", "broker-key");
        File brokerCertFile = File.createTempFile("tls", "broker-cert");

        // generate the missing number of certificates
        // scale up -> generate new certificates for added replicas
        // scale down -> does nothing
        for (int i = replicasInSecret; i < replicas; i++) {
            String podName = podNameFn.apply(i);
            log.debug("{} to generate", podName);

            Subject sbj = new Subject();
            sbj.setOrganizationName("io.strimzi");
            sbj.setCommonName(name);

            sbj.setSubjectAltNames(sanFn.apply(i));

            certManager.generateCsr(brokerKeyFile, brokerCsrFile, sbj);
            certManager.generateCert(brokerCsrFile, clusterCa.key(), clusterCa.cert(), brokerCertFile,
                    sbj, ModelUtils.getCertificateValidity(kafka));

            certs.put(podName,
                    new CertAndKey(Files.readAllBytes(brokerKeyFile.toPath()), Files.readAllBytes(brokerCertFile.toPath())));
        }

        if (!brokerCsrFile.delete()) {
            log.warn("{} cannot be deleted", brokerCsrFile.getName());
        }
        if (!brokerKeyFile.delete()) {
            log.warn("{} cannot be deleted", brokerKeyFile.getName());
        }
        if (!brokerCertFile.delete()) {
            log.warn("{} cannot be deleted", brokerCertFile.getName());
        }

        return certs;
    }

    public Map<String, CertAndKey> generateZkCerts(Kafka kafka) throws IOException {
        String cluster = kafka.getMetadata().getName();
        String namespace = kafka.getMetadata().getNamespace();
        Function<Integer, Map<String, String>> san = i -> {
            Map<String, String> sbjAltNames = new HashMap<>();
            sbjAltNames.put("DNS.1", ZookeeperCluster.serviceName(cluster));
            sbjAltNames.put("DNS.2", String.format("%s.%s.svc.%s", ZookeeperCluster.serviceName(cluster), namespace, KUBERNETES_SERVICE_DNS_DOMAIN));
            sbjAltNames.put("DNS.3", String.format("%s.%s.%s.svc.%s", ZookeeperCluster.zookeeperPodName(cluster, i),
                    ZookeeperCluster.headlessServiceName(cluster), namespace, KUBERNETES_SERVICE_DNS_DOMAIN));
            return sbjAltNames;
        };

        log.debug("Cluster communication certificates");
        return maybeCopyOrGenerateCerts(
            kafka.getSpec().getZookeeper().getReplicas(),
            ZookeeperCluster.zookeeperClusterName(cluster),
            san,
            kafka,
            zkNodesSecret,
            podNum -> ZookeeperCluster.zookeeperPodName(cluster, podNum));
    }

    public Map<String, CertAndKey> generateBrokerCerts(Kafka kafka) throws IOException {
        String cluster = kafka.getMetadata().getName();
        String namespace = kafka.getMetadata().getNamespace();
        log.debug("Internal communication certificates");
        Function<Integer, Map<String, String>> san = i -> {
            Map<String, String> sbjAltNames = new HashMap<>();
            sbjAltNames.put("DNS.1", KafkaCluster.serviceName(cluster));
            sbjAltNames.put("DNS.2", String.format("%s.%s.svc.%s", KafkaCluster.serviceName(cluster), namespace, KUBERNETES_SERVICE_DNS_DOMAIN));
            sbjAltNames.put("DNS.3", String.format("%s.%s.%s.svc.%s", KafkaCluster.kafkaPodName(cluster, i), KafkaCluster.headlessServiceName(cluster), namespace, KUBERNETES_SERVICE_DNS_DOMAIN));
            return sbjAltNames;
        };

        return maybeCopyOrGenerateCerts(
            kafka.getSpec().getKafka().getReplicas(),
            KafkaCluster.kafkaClusterName(cluster),
            san,
            kafka,
            brokersSecret,
            podNum -> KafkaCluster.kafkaPodName(cluster, podNum));
    }
}
