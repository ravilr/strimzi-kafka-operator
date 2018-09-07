/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.extensions.NetworkPolicy;
import io.fabric8.kubernetes.api.model.extensions.NetworkPolicyBuilder;
import io.fabric8.kubernetes.api.model.extensions.NetworkPolicyIngressRule;
import io.fabric8.kubernetes.api.model.extensions.NetworkPolicyIngressRuleBuilder;
import io.fabric8.kubernetes.api.model.extensions.NetworkPolicyPeer;
import io.fabric8.kubernetes.api.model.extensions.NetworkPolicyPort;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.strimzi.api.kafka.model.EphemeralStorage;
import io.strimzi.api.kafka.model.InlineLogging;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaAuthorization;
import io.strimzi.api.kafka.model.KafkaAuthorizationSimple;
import io.strimzi.api.kafka.model.KafkaClusterSpec;
import io.strimzi.api.kafka.model.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.KafkaListeners;
import io.strimzi.api.kafka.model.Logging;
import io.strimzi.api.kafka.model.PersistentClaimStorage;
import io.strimzi.api.kafka.model.Rack;
import io.strimzi.api.kafka.model.Resources;
import io.strimzi.api.kafka.model.Sidecar;
import io.strimzi.certs.CertAndKey;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.resource.ClusterRoleBindingOperator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;

public class KafkaCluster extends AbstractModel {

    protected static final String INIT_NAME = "kafka-init";
    protected static final String RACK_VOLUME_NAME = "rack-volume";
    protected static final String RACK_VOLUME_MOUNT = "/opt/kafka/rack";
    private static final String ENV_VAR_KAFKA_INIT_RACK_TOPOLOGY_KEY = "RACK_TOPOLOGY_KEY";
    private static final String ENV_VAR_KAFKA_INIT_NODE_NAME = "NODE_NAME";
    /** {@code TRUE} when the CLIENT listener (PLAIN transport) should be enabled*/
    private static final String ENV_VAR_KAFKA_CLIENT_ENABLED = "KAFKA_CLIENT_ENABLED";
    /** The authentication to configure for the CLIENT listener (PLAIN transport). */
    private static final String ENV_VAR_KAFKA_CLIENT_AUTHENTICATION = "KAFKA_CLIENT_AUTHENTICATION";
    /** {@code TRUE} when the CLIENTTLS listener (TLS transport) should be enabled*/
    private static final String ENV_VAR_KAFKA_CLIENTTLS_ENABLED = "KAFKA_CLIENTTLS_ENABLED";
    /** The authentication to configure for the CLIENTTLS listener (TLS transport) . */
    private static final String ENV_VAR_KAFKA_CLIENTTLS_AUTHENTICATION = "KAFKA_CLIENTTLS_AUTHENTICATION";
    private static final String ENV_VAR_KAFKA_AUTHORIZATION_TYPE = "KAFKA_AUTHORIZATION_TYPE";
    private static final String ENV_VAR_KAFKA_AUTHORIZATION_SUPER_USERS = "KAFKA_AUTHORIZATION_SUPER_USERS";

    protected static final int CLIENT_PORT = 9092;
    protected static final String CLIENT_PORT_NAME = "clients";

    protected static final int REPLICATION_PORT = 9091;
    protected static final String REPLICATION_PORT_NAME = "replication";

    protected static final int CLIENT_TLS_PORT = 9093;
    protected static final String CLIENT_TLS_PORT_NAME = "clientstls";

    protected static final String KAFKA_NAME = "kafka";
    protected static final String BROKER_CERTS_VOLUME = "broker-certs";
    protected static final String CLIENT_CA_CERTS_VOLUME = "client-ca-cert";
    protected static final String BROKER_CERTS_VOLUME_MOUNT = "/opt/kafka/broker-certs";
    protected static final String CLIENT_CA_CERTS_VOLUME_MOUNT = "/opt/kafka/client-ca-cert";
    protected static final String TLS_SIDECAR_NAME = "tls-sidecar";
    protected static final String TLS_SIDECAR_VOLUME_MOUNT = "/etc/tls-sidecar/certs/";

    private static final String NAME_SUFFIX = "-kafka";
    private static final String SERVICE_NAME_SUFFIX = NAME_SUFFIX + "-bootstrap";
    private static final String HEADLESS_SERVICE_NAME_SUFFIX = NAME_SUFFIX + "-brokers";

    // Suffixes for secrets with certificates
    private static final String SECRET_BROKERS_SUFFIX = NAME_SUFFIX + "-brokers";
    private static final String SECRET_CLUSTER_PUBLIC_KEY_SUFFIX = "-cert";
    private static final String SECRET_CLIENTS_CA_SUFFIX = "-clients-ca";
    private static final String SECRET_CLIENTS_PUBLIC_KEY_SUFFIX = "-clients-ca-cert";

    protected static final String METRICS_AND_LOG_CONFIG_SUFFIX = NAME_SUFFIX + "-config";

    // Kafka configuration
    private String zookeeperConnect;
    private Rack rack;
    private String initImage;
    private Sidecar tlsSidecar;
    private KafkaListeners listeners;
    private KafkaAuthorization authorization;

    // Configuration defaults
    private static final int DEFAULT_REPLICAS = 3;
    private static final int DEFAULT_HEALTHCHECK_DELAY = 15;
    private static final int DEFAULT_HEALTHCHECK_TIMEOUT = 5;
    private static final boolean DEFAULT_KAFKA_METRICS_ENABLED = false;

    // Kafka configuration keys (EnvVariables)
    public static final String ENV_VAR_KAFKA_ZOOKEEPER_CONNECT = "KAFKA_ZOOKEEPER_CONNECT";
    private static final String ENV_VAR_KAFKA_METRICS_ENABLED = "KAFKA_METRICS_ENABLED";
    protected static final String ENV_VAR_KAFKA_CONFIGURATION = "KAFKA_CONFIGURATION";
    protected static final String ENV_VAR_KAFKA_LOG_CONFIGURATION = "KAFKA_LOG_CONFIGURATION";

    private CertAndKey clientsCA;
    /**
     * Private key and certificate for each Kafka Pod name
     * used as server certificates for Kafka brokers
     */
    private Map<String, CertAndKey> brokerCerts;

    /**
     * Constructor
     *
     * @param namespace Kubernetes/OpenShift namespace where Kafka cluster resources are going to be created
     * @param cluster  overall cluster name
     */
    private KafkaCluster(String namespace, String cluster, Labels labels) {
        super(namespace, cluster, labels);
        this.name = kafkaClusterName(cluster);
        this.serviceName = serviceName(cluster);
        this.headlessServiceName = headlessServiceName(cluster);
        this.ancillaryConfigName = metricAndLogConfigsName(cluster);
        this.image = KafkaClusterSpec.DEFAULT_IMAGE;
        this.replicas = DEFAULT_REPLICAS;
        this.readinessTimeout = DEFAULT_HEALTHCHECK_TIMEOUT;
        this.readinessInitialDelay = DEFAULT_HEALTHCHECK_DELAY;
        this.livenessTimeout = DEFAULT_HEALTHCHECK_TIMEOUT;
        this.livenessInitialDelay = DEFAULT_HEALTHCHECK_DELAY;
        this.isMetricsEnabled = DEFAULT_KAFKA_METRICS_ENABLED;

        setZookeeperConnect(ZookeeperCluster.serviceName(cluster) + ":2181");

        this.mountPath = "/var/lib/kafka";

        this.logAndMetricsConfigVolumeName = "kafka-metrics-and-logging";
        this.logAndMetricsConfigMountPath = "/opt/kafka/custom-config/";

        this.initImage = KafkaClusterSpec.DEFAULT_INIT_IMAGE;
        this.validLoggerFields = getDefaultLogConfig();
    }

    public static String kafkaClusterName(String cluster) {
        return cluster + KafkaCluster.NAME_SUFFIX;
    }

    public static String metricAndLogConfigsName(String cluster) {
        return cluster + KafkaCluster.METRICS_AND_LOG_CONFIG_SUFFIX;
    }

    public static String serviceName(String cluster) {
        return cluster + KafkaCluster.SERVICE_NAME_SUFFIX;
    }

    public static String headlessServiceName(String cluster) {
        return cluster + KafkaCluster.HEADLESS_SERVICE_NAME_SUFFIX;
    }

    public static String kafkaPodName(String cluster, int pod) {
        return kafkaClusterName(cluster) + "-" + pod;
    }

    public static String clientsCASecretName(String cluster) {
        return cluster + KafkaCluster.SECRET_CLIENTS_CA_SUFFIX;
    }

    public static String brokersSecretName(String cluster) {
        return cluster + KafkaCluster.SECRET_BROKERS_SUFFIX;
    }

    public static String clientsPublicKeyName(String cluster) {
        return cluster + KafkaCluster.SECRET_CLIENTS_PUBLIC_KEY_SUFFIX;
    }

    public static String clusterPublicKeyName(String cluster) {
        return getClusterCaName(cluster) + KafkaCluster.SECRET_CLUSTER_PUBLIC_KEY_SUFFIX;
    }

    public static KafkaCluster fromCrd(Kafka kafkaAssembly, Certificates secrets) {
        KafkaCluster result = new KafkaCluster(kafkaAssembly.getMetadata().getNamespace(),
                kafkaAssembly.getMetadata().getName(),
                Labels.fromResource(kafkaAssembly).withKind(kafkaAssembly.getKind()));
        KafkaClusterSpec kafkaClusterSpec = kafkaAssembly.getSpec().getKafka();
        result.setReplicas(kafkaClusterSpec.getReplicas());
        String image = kafkaClusterSpec.getImage();
        if (image == null) {
            image = KafkaClusterSpec.DEFAULT_IMAGE;
        }
        result.setImage(image);
        if (kafkaClusterSpec.getReadinessProbe() != null) {
            result.setReadinessInitialDelay(kafkaClusterSpec.getReadinessProbe().getInitialDelaySeconds());
            result.setReadinessTimeout(kafkaClusterSpec.getReadinessProbe().getTimeoutSeconds());
        }
        if (kafkaClusterSpec.getLivenessProbe() != null) {
            result.setLivenessInitialDelay(kafkaClusterSpec.getLivenessProbe().getInitialDelaySeconds());
            result.setLivenessTimeout(kafkaClusterSpec.getLivenessProbe().getTimeoutSeconds());
        }
        result.setRack(kafkaClusterSpec.getRack());

        String initImage = kafkaClusterSpec.getBrokerRackInitImage();
        if (initImage == null) {
            initImage = KafkaClusterSpec.DEFAULT_INIT_IMAGE;
        }
        result.setInitImage(initImage);
        Logging logging = kafkaClusterSpec.getLogging();
        result.setLogging(logging == null ? new InlineLogging() : logging);
        result.setJvmOptions(kafkaClusterSpec.getJvmOptions());
        result.setConfiguration(new KafkaConfiguration(kafkaClusterSpec.getConfig().entrySet()));
        Map<String, Object> metrics = kafkaClusterSpec.getMetrics();
        if (metrics != null && !metrics.isEmpty()) {
            result.setMetricsEnabled(true);
            result.setMetricsConfig(metrics.entrySet());
        }
        result.setStorage(kafkaClusterSpec.getStorage());
        result.setUserAffinity(kafkaClusterSpec.getAffinity());
        result.setResources(kafkaClusterSpec.getResources());
        result.setTolerations(kafkaClusterSpec.getTolerations());

        result.generateCertificates(kafkaAssembly, secrets);
        result.setTlsSidecar(kafkaClusterSpec.getTlsSidecar());

        KafkaListeners listeners = kafkaClusterSpec.getListeners();
        if (listeners != null) {
            if (listeners.getPlain() != null
                && listeners.getPlain().getAuthentication() instanceof KafkaListenerAuthenticationTls) {
                throw new InvalidResourceException("You cannot configure TLS authentication on a plain listener.");
            }
        }
        result.setListeners(listeners);
        result.setAuthorization(kafkaClusterSpec.getAuthorization());

        return result;
    }

    /**
     * Manage certificates generation based on those already present in the Secrets
     * @param kafka The kafka CR
     * @param certificates The certificates
     */
    public void generateCertificates(Kafka kafka, Certificates certificates) {
        log.debug("Generating certificates");

        try {
            clusterCA = certificates.clusterCa();
            clientsCA = certificates.clientsCa(kafka);
            brokerCerts = certificates.generateBrokerCerts(kafka);
        } catch (IOException e) {
            log.warn("Error while generating certificates", e);
        }

        log.debug("End generating certificates");
    }

    /**
     * Generates ports for bootstrap service.
     * The bootstrap service contains only the client interfaces.
     * Not the replication interface which doesn't need bootstrap service.
     *
     * @return List with generated ports
     */
    private List<ServicePort> getServicePorts() {
        List<ServicePort> ports = new ArrayList<>(4);
        ports.add(createServicePort(REPLICATION_PORT_NAME, REPLICATION_PORT, REPLICATION_PORT, "TCP"));

        if (listeners != null && listeners.getPlain() != null) {
            ports.add(createServicePort(CLIENT_PORT_NAME, CLIENT_PORT, CLIENT_PORT, "TCP"));
        }

        if (listeners != null && listeners.getTls() != null) {
            ports.add(createServicePort(CLIENT_TLS_PORT_NAME, CLIENT_TLS_PORT, CLIENT_TLS_PORT, "TCP"));
        }

        if (isMetricsEnabled()) {
            ports.add(createServicePort(METRICS_PORT_NAME, METRICS_PORT, METRICS_PORT, "TCP"));
        }
        return ports;
    }

    /**
     * Generates ports for headless service.
     * The headless service contains both the client interfaces as well as replication interface.
     *
     * @return List with generated ports
     */
    private List<ServicePort> getHeadlessServicePorts() {
        List<ServicePort> ports = new ArrayList<>(3);
        ports.add(createServicePort(REPLICATION_PORT_NAME, REPLICATION_PORT, REPLICATION_PORT, "TCP"));

        if (listeners != null && listeners.getPlain() != null) {
            ports.add(createServicePort(CLIENT_PORT_NAME, CLIENT_PORT, CLIENT_PORT, "TCP"));
        }

        if (listeners != null && listeners.getTls() != null) {
            ports.add(createServicePort(CLIENT_TLS_PORT_NAME, CLIENT_TLS_PORT, CLIENT_TLS_PORT, "TCP"));
        }

        return ports;
    }

    /**
     * Generates a Service according to configured defaults
     * @return The generated Service
     */
    public Service generateService() {
        return createService("ClusterIP", getServicePorts(), getPrometheusAnnotations());
    }

    /**
     * Generates a headless Service according to configured defaults
     * @return The generated Service
     */
    public Service generateHeadlessService() {
        Map<String, String> annotations = Collections.singletonMap("service.alpha.kubernetes.io/tolerate-unready-endpoints", "true");
        return createHeadlessService(getHeadlessServicePorts(), annotations);
    }

    /**
     * Generates a StatefulSet according to configured defaults
     * @param isOpenShift True iff this operator is operating within OpenShift.
     * @return The generate StatefulSet
     */
    public StatefulSet generateStatefulSet(boolean isOpenShift) {

        return createStatefulSet(
                getVolumes(),
                getVolumeClaims(),
                getVolumeMounts(),
                getMergedAffinity(),
                getInitContainers(),
                getContainers(),
                isOpenShift);
    }

    /**
     * Generate the Secret containing CA private key and self-signed certificate used
     * for signing brokers certificates used for communication with clients
     * @return The generated Secret
     */
    public Secret generateClientsCASecret() {
        Map<String, String> data = new HashMap<>();
        data.put("clients-ca.key", clientsCA.keyAsBase64String());
        data.put("clients-ca.crt", clientsCA.certAsBase64String());
        return createSecret(KafkaCluster.clientsCASecretName(cluster), data);
    }

    /**
     * Generate the Secret containing just the self-signed CA certificate used
     * for signing client certificates. It is used for the broker truststore.
     * @return The generated Secret
     */
    public Secret generateClientsPublicKeySecret() {
        Map<String, String> data = new HashMap<>();
        data.put("ca.crt", Base64.getEncoder().encodeToString(clientsCA.cert()));
        return createSecret(KafkaCluster.clientsPublicKeyName(cluster), data);
    }

    /**
     * Generate the Secret containing just the self-signed CA certificate used
     * for signing brokers certificates used for communication with clients
     * It's useful for users to extract the certificate itself to put as trusted on the clients
     * @return The generated Secret
     */
    public Secret generateClusterPublicKeySecret() {
        Map<String, String> data = new HashMap<>();
        data.put("ca.crt", Base64.getEncoder().encodeToString(clusterCA.cert()));
        return createSecret(KafkaCluster.clusterPublicKeyName(cluster), data);
    }

    /**
     * Generate the Secret containing CA self-signed certificate for TLS communication.
     * It also contains the private key-certificate (signed by cluster CA) for each brokers as well as for communicating
     * with Zookeeper as well
     * @return The generated Secret
     */
    public Secret generateBrokersSecret() {

        Map<String, String> data = new HashMap<>();
        data.put("cluster-ca.crt", clusterCA.certAsBase64String());

        for (int i = 0; i < replicas; i++) {
            CertAndKey cert = brokerCerts.get(KafkaCluster.kafkaPodName(cluster, i));
            data.put(KafkaCluster.kafkaPodName(cluster, i) + ".key", cert.keyAsBase64String());
            data.put(KafkaCluster.kafkaPodName(cluster, i) + ".crt", cert.certAsBase64String());
        }
        return createSecret(KafkaCluster.brokersSecretName(cluster), data);
    }

    private List<ContainerPort> getContainerPortList() {
        List<ContainerPort> portList = new ArrayList<>(3);
        portList.add(createContainerPort(REPLICATION_PORT_NAME, REPLICATION_PORT, "TCP"));

        if (listeners != null && listeners.getPlain() != null) {
            portList.add(createContainerPort(CLIENT_PORT_NAME, CLIENT_PORT, "TCP"));
        }

        if (listeners != null && listeners.getTls() != null) {
            portList.add(createContainerPort(CLIENT_TLS_PORT_NAME, CLIENT_TLS_PORT, "TCP"));
        }

        if (isMetricsEnabled) {
            portList.add(createContainerPort(METRICS_PORT_NAME, METRICS_PORT, "TCP"));
        }

        return portList;
    }

    private List<Volume> getVolumes() {
        List<Volume> volumeList = new ArrayList<>();
        if (storage instanceof EphemeralStorage) {
            volumeList.add(createEmptyDirVolume(VOLUME_NAME));
        }

        if (rack != null) {
            volumeList.add(createEmptyDirVolume(RACK_VOLUME_NAME));
        }
        volumeList.add(createSecretVolume(BROKER_CERTS_VOLUME, KafkaCluster.brokersSecretName(cluster)));
        volumeList.add(createSecretVolume(CLIENT_CA_CERTS_VOLUME, KafkaCluster.clientsPublicKeyName(cluster)));
        volumeList.add(createConfigMapVolume(logAndMetricsConfigVolumeName, ancillaryConfigName));

        return volumeList;
    }

    private List<PersistentVolumeClaim> getVolumeClaims() {
        List<PersistentVolumeClaim> pvcList = new ArrayList<>();
        if (storage instanceof PersistentClaimStorage) {
            pvcList.add(createPersistentVolumeClaim(VOLUME_NAME));
        }
        return pvcList;
    }

    private List<VolumeMount> getVolumeMounts() {
        List<VolumeMount> volumeMountList = new ArrayList<>();
        volumeMountList.add(createVolumeMount(VOLUME_NAME, mountPath));

        volumeMountList.add(createVolumeMount(BROKER_CERTS_VOLUME, BROKER_CERTS_VOLUME_MOUNT));
        volumeMountList.add(createVolumeMount(CLIENT_CA_CERTS_VOLUME, CLIENT_CA_CERTS_VOLUME_MOUNT));
        volumeMountList.add(createVolumeMount(logAndMetricsConfigVolumeName, logAndMetricsConfigMountPath));

        if (rack != null) {
            volumeMountList.add(createVolumeMount(RACK_VOLUME_NAME, RACK_VOLUME_MOUNT));
        }

        return volumeMountList;
    }

    /**
     * Returns a combined affinity: Adding the affinity needed for the "kafka-rack" to the {@link #getUserAffinity()}.
     */
    @Override
    protected Affinity getMergedAffinity() {
        Affinity userAffinity = getUserAffinity();
        AffinityBuilder builder = new AffinityBuilder(userAffinity == null ? new Affinity() : userAffinity);
        if (rack != null) {
            // If there's a rack config, we need to add a podAntiAffinity to spread the brokers among the racks
            builder = builder
                    .editOrNewPodAntiAffinity()
                        .addNewPreferredDuringSchedulingIgnoredDuringExecution()
                            .withWeight(100)
                            .withNewPodAffinityTerm()
                                .withTopologyKey(rack.getTopologyKey())
                                .withNewLabelSelector()
                                    .addToMatchLabels(Labels.STRIMZI_CLUSTER_LABEL, cluster)
                                    .addToMatchLabels(Labels.STRIMZI_NAME_LABEL, name)
                                .endLabelSelector()
                            .endPodAffinityTerm()
                        .endPreferredDuringSchedulingIgnoredDuringExecution()
                    .endPodAntiAffinity();
        }
        return builder.build();
    }

    @Override
    protected List<Container> getInitContainers() {

        List<Container> initContainers = new ArrayList<>();

        if (rack != null) {

            ResourceRequirements resources = new ResourceRequirementsBuilder()
                    .addToRequests("cpu", new Quantity("100m"))
                    .addToRequests("memory", new Quantity("128Mi"))
                    .addToLimits("cpu", new Quantity("1"))
                    .addToLimits("memory", new Quantity("256Mi"))
                    .build();

            List<EnvVar> varList =
                    Arrays.asList(buildEnvVarFromFieldRef(ENV_VAR_KAFKA_INIT_NODE_NAME, "spec.nodeName"),
                            buildEnvVar(ENV_VAR_KAFKA_INIT_RACK_TOPOLOGY_KEY, rack.getTopologyKey()));

            Container initContainer = new ContainerBuilder()
                    .withName(INIT_NAME)
                    .withImage(initImage)
                    .withResources(resources)
                    .withEnv(varList)
                    .withVolumeMounts(createVolumeMount(RACK_VOLUME_NAME, RACK_VOLUME_MOUNT))
                    .build();

            initContainers.add(initContainer);
        }

        return initContainers;
    }

    @Override
    protected List<Container> getContainers() {

        List<Container> containers = new ArrayList<>();

        Container container = new ContainerBuilder()
                .withName(KAFKA_NAME)
                .withImage(getImage())
                .withEnv(getEnvVars())
                .withVolumeMounts(getVolumeMounts())
                .withPorts(getContainerPortList())
                .withLivenessProbe(createTcpSocketProbe(REPLICATION_PORT, livenessInitialDelay, livenessTimeout))
                .withReadinessProbe(createTcpSocketProbe(REPLICATION_PORT, readinessInitialDelay, readinessTimeout))
                .withResources(resources(getResources()))
                .build();

        String tlsSidecarImage = (tlsSidecar != null && tlsSidecar.getImage() != null) ?
                tlsSidecar.getImage() : KafkaClusterSpec.DEFAULT_TLS_SIDECAR_IMAGE;

        Resources tlsSidecarResources = (tlsSidecar != null) ? tlsSidecar.getResources() : null;

        Container tlsSidecarContainer = new ContainerBuilder()
                .withName(TLS_SIDECAR_NAME)
                .withImage(tlsSidecarImage)
                .withResources(resources(tlsSidecarResources))
                .withEnv(singletonList(buildEnvVar(ENV_VAR_KAFKA_ZOOKEEPER_CONNECT, zookeeperConnect)))
                .withVolumeMounts(createVolumeMount(BROKER_CERTS_VOLUME, TLS_SIDECAR_VOLUME_MOUNT))
                .build();

        containers.add(container);
        containers.add(tlsSidecarContainer);

        return containers;
    }

    @Override
    protected String getServiceAccountName() {
        return initContainerServiceAccountName(cluster);
    }

    @Override
    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(buildEnvVar(ENV_VAR_KAFKA_ZOOKEEPER_CONNECT, zookeeperConnect));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_METRICS_ENABLED, String.valueOf(isMetricsEnabled)));
        heapOptions(varList, 0.5, 5L * 1024L * 1024L * 1024L);
        jvmPerformanceOptions(varList);

        if (configuration != null && !configuration.getConfiguration().isEmpty()) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_CONFIGURATION, configuration.getConfiguration()));
        }

        if (listeners != null)  {
            if (listeners.getPlain() != null)   {
                varList.add(buildEnvVar(ENV_VAR_KAFKA_CLIENT_ENABLED, "TRUE"));

                if (listeners.getPlain().getAuthentication() != null) {
                    varList.add(buildEnvVar(ENV_VAR_KAFKA_CLIENT_AUTHENTICATION, listeners.getPlain().getAuthentication().getType()));
                }
            }

            if (listeners.getTls() != null) {
                varList.add(buildEnvVar(ENV_VAR_KAFKA_CLIENTTLS_ENABLED, "TRUE"));

                if (listeners.getTls().getAuth() != null) {
                    varList.add(buildEnvVar(ENV_VAR_KAFKA_CLIENTTLS_AUTHENTICATION, listeners.getTls().getAuth().getType()));
                }
            }
        }

        if (authorization != null && KafkaAuthorizationSimple.TYPE_SIMPLE.equals(authorization.getType()))  {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_AUTHORIZATION_TYPE, KafkaAuthorizationSimple.TYPE_SIMPLE));

            KafkaAuthorizationSimple simpleAuthz = (KafkaAuthorizationSimple) authorization;
            if (simpleAuthz.getSuperUsers() != null && simpleAuthz.getSuperUsers().size() > 0)  {
                String superUsers = simpleAuthz.getSuperUsers().stream().map(e -> String.format("User:%s", e)).collect(Collectors.joining(";"));
                varList.add(buildEnvVar(ENV_VAR_KAFKA_AUTHORIZATION_SUPER_USERS, superUsers));
            }
        }

        return varList;
    }

    protected void setZookeeperConnect(String zookeeperConnect) {
        this.zookeeperConnect = zookeeperConnect;
    }

    protected void setRack(Rack rack) {
        this.rack = rack;
    }

    protected void setInitImage(String initImage) {
        this.initImage = initImage;
    }

    protected void setTlsSidecar(Sidecar tlsSidecar) {
        this.tlsSidecar = tlsSidecar;
    }

    @Override
    protected String getDefaultLogConfigFileName() {
        return "kafkaDefaultLoggingProperties";
    }

    public ServiceAccount generateInitContainerServiceAccount() {
        return new ServiceAccountBuilder()
                .withNewMetadata()
                    .withName(initContainerServiceAccountName(cluster))
                    .withNamespace(namespace)
                .endMetadata()
            .build();
    }


    /**
     * Get the name of the kafka service account given the name of the {@code kafkaResourceName}.
     */
    public static String initContainerServiceAccountName(String kafkaResourceName) {
        return kafkaClusterName(kafkaResourceName);
    }

    /**
     * Get the name of the kafka init container role binding given the name of the {@code namespace} and {@code cluster}.
     */
    public static String initContainerClusterRoleBindingName(String namespace, String cluster) {
        return "strimzi-" + namespace + "-" + cluster + "-kafka-init";
    }

    /**
     * Creates the ClusterRoleBinding which is used to bind the Kafka SA to the ClusterRole
     * which permissions the Kafka init container to access K8S nodes (necessary for rack-awareness).
     */
    public ClusterRoleBindingOperator.ClusterRoleBinding generateClusterRoleBinding(String assemblyNamespace) {
        if (rack != null) {
            return new ClusterRoleBindingOperator.ClusterRoleBinding(
                    initContainerClusterRoleBindingName(namespace, cluster),
                    "strimzi-kafka-broker",
                    assemblyNamespace, initContainerServiceAccountName(cluster));
        } else {
            return null;
        }
    }

    public static String policyName(String cluster) {
        return cluster + NETWORK_POLICY_KEY_SUFFIX + NAME_SUFFIX;
    }

    public NetworkPolicy generateNetworkPolicy() {
        NetworkPolicyPort port = new NetworkPolicyPort();
        port.setPort(new IntOrString(REPLICATION_PORT));

        NetworkPolicyPeer kafkaClusterPeer = new NetworkPolicyPeer();
        LabelSelector labelSelector = new LabelSelector();
        Map<String, String> expressions = new HashMap<>();
        expressions.put(Labels.STRIMZI_NAME_LABEL, kafkaClusterName(cluster));
        labelSelector.setMatchLabels(expressions);
        kafkaClusterPeer.setPodSelector(labelSelector);

        NetworkPolicyPeer entityOperatorPeer = new NetworkPolicyPeer();
        LabelSelector labelSelector2 = new LabelSelector();
        Map<String, String> expressions2 = new HashMap<>();
        expressions2.put(Labels.STRIMZI_NAME_LABEL, EntityOperator.entityOperatorName(cluster));
        labelSelector2.setMatchLabels(expressions2);
        entityOperatorPeer.setPodSelector(labelSelector2);


        NetworkPolicyIngressRule networkPolicyIngressRule = new NetworkPolicyIngressRuleBuilder()
                .withPorts(port)
                .withFrom(kafkaClusterPeer, entityOperatorPeer)
                .build();

        NetworkPolicy networkPolicy = new NetworkPolicyBuilder()
                .withNewMetadata()
                .withName(policyName(cluster))
                .withNamespace(namespace)
                .withLabels(labels.toMap())
                .endMetadata()
                .withNewSpec()
                .withPodSelector(labelSelector)
                .withIngress(networkPolicyIngressRule)
                .endSpec()
                .build();

        log.trace("Created network policy {}", networkPolicy);
        return networkPolicy;
    }
    /**
     * Sets the object with Kafka listeners configuration
     *
     * @param listeners
     */
    public void setListeners(KafkaListeners listeners) {
        this.listeners = listeners;
    }

    /**
     * Sets the object with Kafka authorization configuration
     *
     * @param authorization
     */
    public void setAuthorization(KafkaAuthorization authorization) {
        this.authorization = authorization;
    }
}