/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.KafkaAssemblyList;
import io.strimzi.api.kafka.model.DoneableKafka;
import io.strimzi.api.kafka.model.ExternalLogging;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.TlsCertificates;
import io.strimzi.certs.CertManager;
import io.strimzi.certs.SecretCertProvider;
import io.strimzi.certs.Subject;
import io.strimzi.operator.cluster.model.AbstractModel;
import io.strimzi.operator.cluster.model.EntityOperator;
import io.strimzi.operator.cluster.model.EntityTopicOperator;
import io.strimzi.operator.cluster.model.EntityUserOperator;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.cluster.model.ModelUtils;
import io.strimzi.operator.cluster.model.TopicOperator;
import io.strimzi.operator.cluster.model.ZookeeperCluster;
import io.strimzi.operator.cluster.operator.resource.KafkaSetOperator;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.cluster.operator.resource.ZookeeperSetOperator;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.model.ResourceType;
import io.strimzi.operator.common.operator.resource.ClusterRoleBindingOperator;
import io.strimzi.operator.common.operator.resource.ConfigMapOperator;
import io.strimzi.operator.common.operator.resource.DeploymentOperator;
import io.strimzi.operator.common.operator.resource.PvcOperator;
import io.strimzi.operator.common.operator.resource.ReconcileResult;
import io.strimzi.operator.common.operator.resource.RoleBindingOperator;
import io.strimzi.operator.common.operator.resource.ServiceAccountOperator;
import io.strimzi.operator.common.operator.resource.ServiceOperator;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.strimzi.operator.cluster.model.ModelUtils.findSecretWithName;
import static java.util.Collections.emptyMap;

/**
 * <p>Assembly operator for a "Kafka" assembly, which manages:</p>
 * <ul>
 *     <li>A ZooKeeper cluster StatefulSet and related Services</li>
 *     <li>A Kafka cluster StatefulSet and related Services</li>
 *     <li>Optionally, a TopicOperator Deployment</li>
 * </ul>
 */
public class KafkaAssemblyOperator extends AbstractAssemblyOperator<KubernetesClient, Kafka, KafkaAssemblyList, DoneableKafka, Resource<Kafka, DoneableKafka>> {
    private static final Logger log = LogManager.getLogger(KafkaAssemblyOperator.class.getName());


    private final long operationTimeoutMs;

    private final ZookeeperSetOperator zkSetOperations;
    private final KafkaSetOperator kafkaSetOperations;
    private final ServiceOperator serviceOperations;
    private final PvcOperator pvcOperations;
    private final DeploymentOperator deploymentOperations;
    private final ConfigMapOperator configMapOperations;
    private final ServiceAccountOperator serviceAccountOperator;
    private final RoleBindingOperator roleBindingOperator;
    private final ClusterRoleBindingOperator clusterRoleBindingOperator;

    /**
     * @param vertx The Vertx instance
     * @param isOpenShift Whether we're running with OpenShift
     */
    public KafkaAssemblyOperator(Vertx vertx, boolean isOpenShift,
                                 long operationTimeoutMs,
                                 CertManager certManager,
                                 ResourceOperatorSupplier supplier) {
        super(vertx, isOpenShift, ResourceType.KAFKA, certManager, supplier.kafkaOperator, supplier.secretOperations, supplier.networkPolicyOperator);
        this.operationTimeoutMs = operationTimeoutMs;
        this.serviceOperations = supplier.serviceOperations;
        this.zkSetOperations = supplier.zkSetOperations;
        this.kafkaSetOperations = supplier.kafkaSetOperations;
        this.configMapOperations = supplier.configMapOperations;
        this.pvcOperations = supplier.pvcOperations;
        this.deploymentOperations = supplier.deploymentOperations;
        this.serviceAccountOperator = supplier.serviceAccountOperator;
        this.roleBindingOperator = supplier.roleBindingOperator;
        this.clusterRoleBindingOperator = supplier.clusterRoleBindingOperator;
    }

    @Override
    public Future<Void> createOrUpdate(Reconciliation reconciliation, Kafka kafkaAssembly) {
        Future<Void> chainFuture = Future.future();
        new ReconciliationState(reconciliation, kafkaAssembly)
                .reconcileClusterCa()
                // Roll everything so the new CA is added to the trust store.
                .compose(state -> state.rollingUpdateForNewCaCert())

                .compose(state -> state.getZookeeperState())
                .compose(state -> state.zkScaleDown())
                .compose(state -> state.zkService())
                .compose(state -> state.zkHeadlessService())
                .compose(state -> state.zkAncillaryCm())
                .compose(state -> state.zkNodesSecret())
                .compose(state -> state.zkNetPolicy())
                .compose(state -> state.zkStatefulSet())
                .compose(state -> state.zkRollingUpdate())
                .compose(state -> state.zkScaleUp())
                .compose(state -> state.zkServiceEndpointReadiness())
                .compose(state -> state.zkHeadlessServiceEndpointReadiness())

                .compose(state -> state.getKafkaClusterDescription())
                .compose(state -> state.kafkaInitServiceAccount())
                .compose(state -> state.kafkaInitClusterRoleBinding())
                .compose(state -> state.kafkaScaleDown())
                .compose(state -> state.kafkaService())
                .compose(state -> state.kafkaHeadlessService())
                .compose(state -> state.kafkaAncillaryCm())
                .compose(state -> state.kafkaClientsCaSecret())
                .compose(state -> state.kafkaClientsPublicKeySecret())
                .compose(state -> state.kafkaClusterPublicKeySecret())
                .compose(state -> state.kafkaBrokersSecret())
                .compose(state -> state.kafkaNetPolicy())
                .compose(state -> state.kafkaStatefulSet())
                .compose(state -> state.kafkaRollingUpdate())
                .compose(state -> state.kafkaScaleUp())
                .compose(state -> state.kafkaServiceEndpointReady())
                .compose(state -> state.kafkaHeadlessServiceEndpointReady())

                .compose(state -> state.getTopicOperatorDescription())
                .compose(state -> state.topicOperatorServiceAccount())
                .compose(state -> state.topicOperatorRoleBinding())
                .compose(state -> state.topicOperatorAncillaryCm())
                .compose(state -> state.topicOperatorSecret())
                .compose(state -> state.topicOperatorDeployment())

                .compose(state -> state.getEntityOperatorDescription())
                .compose(state -> state.entityOperatorServiceAccount())
                .compose(state -> state.entityOperatorTopicOpRoleBinding())
                .compose(state -> state.entityOperatorUserOpRoleBinding())
                .compose(state -> state.entityOperatorTopicOpAncillaryCm())
                .compose(state -> state.entityOperatorUserOpAncillaryCm())
                .compose(state -> state.entityOperatorSecret())
                .compose(state -> state.entityOperatorDeployment())

                .compose(state -> chainFuture.complete(), chainFuture);

        return chainFuture;
    }

    /**
     * Hold the mutable state during a reconciliation
     */
    class ReconciliationState {

        private final String namespace;
        private final String name;
        private final Kafka kafkaAssembly;
        private final Reconciliation reconciliation;

        private List<Secret> assemblySecrets;
        private boolean clusterCaCertRenewed;
        private boolean clusterCaCertsRemoved;

        private ZookeeperCluster zkCluster;
        private Service zkService;
        private Service zkHeadlessService;
        private ConfigMap zkMetricsAndLogsConfigMap;
        private ReconcileResult<StatefulSet> zkDiffs;
        private boolean zkForcedRestart;

        private KafkaCluster kafkaCluster = null;
        private Service kafkaService;
        private Service kafkaHeadlessService;
        private ConfigMap kafkaMetricsAndLogsConfigMap;
        private ReconcileResult<StatefulSet> kafkaDiffs;
        private boolean kafkaForcedRestart;

        private TopicOperator topicOperator;
        private Deployment toDeployment = null;
        private ConfigMap toMetricsAndLogsConfigMap = null;

        private EntityOperator entityOperator;
        private Deployment eoDeployment = null;
        private ConfigMap topicOperatorMetricsAndLogsConfigMap = null;
        private ConfigMap userOperatorMetricsAndLogsConfigMap;

        ReconciliationState(Reconciliation reconciliation, Kafka kafkaAssembly) {
            this.reconciliation = reconciliation;
            this.kafkaAssembly = kafkaAssembly;
            this.namespace = kafkaAssembly.getMetadata().getNamespace();
            this.name = kafkaAssembly.getMetadata().getName();
        }

        /**
         * Asynchronously reconciles the cluster CA secret.
         * The secret has have the name determined by {@link AbstractModel#getClusterCaName(String)}.
         * Within the secret the current certificate is stored under the key {@code cluster-ca.crt}
         * and the current key is stored under the key {@code cluster-ca.crt}.
         * Old certificates which are still within their validity are stored under keys named like {@code cluster-ca-<not-after-date>.crt}, where
         * {@code <not-after-date>} is the ISO 8601-formatted date of the certificates "notAfter" attribute.
         * Likewise, the corresponding keys are stored under keys named like {@code cluster-ca-<not-after-date>.key}.
         */
        Future<ReconciliationState>  reconcileClusterCa() {
            TlsCertificates tlsCertificates = kafkaAssembly.getSpec().getTlsCertificates();
            Labels caLabels = Labels.userLabels(kafkaAssembly.getMetadata().getLabels()).withKind(reconciliation.type().toString()).withCluster(reconciliation.name());
            Future<ReconciliationState> result = Future.future();
            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        String clusterCaName = AbstractModel.getClusterCaName(reconciliation.name());
                        List<Secret> clusterSecrets = secretOperations.list(reconciliation.namespace(), caLabels);
                        Secret clusterCa = findSecretWithName(clusterSecrets, clusterCaName);
                        SecretCertProvider secretCertProvider = new SecretCertProvider();
                        Secret secret;
                        X509Certificate currentCert = cert(clusterCa, CLUSTER_CA_CRT);
                        boolean needsRenewal = currentCert != null && certNeedsRenewal(tlsCertificates, currentCert);
                        final boolean certsRemoved;
                        final Map<String, String> newData;
                        if (tlsCertificates != null && !tlsCertificates.isGenerateCertificateAuthority()) {
                            newData = checkProvidedCert(reconciliation, clusterCaName, clusterCa, currentCert, needsRenewal);
                            certsRemoved = false;
                        } else {
                            if (clusterCa == null || needsRenewal) {
                                newData = createOrRenewCert(reconciliation, tlsCertificates, clusterCaName, clusterCa, currentCert);
                            } else {
                                log.debug("{}: The cluster CA {} already exists and does not need renewing", reconciliation, clusterCaName);
                                newData = clusterCa.getData();
                            }
                            certsRemoved = removeExpiredCerts(newData) > 0;
                        }
                        secret = secretCertProvider.createSecret(reconciliation.namespace(), clusterCaName,
                                newData, caLabels.toMap());

                        secretOperations.reconcile(reconciliation.namespace(), clusterCaName, secret)
                                .compose(x -> {
                                    clusterSecrets.add(secret);
                                    this.assemblySecrets = clusterSecrets;
                                    this.clusterCaCertRenewed = needsRenewal;
                                    this.clusterCaCertsRemoved = certsRemoved;
                                    future.complete(this);
                                }, future);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                    result.completer()
            );
            return result;
        }

        private int removeExpiredCerts(Map<String, String> newData) {
            int removed = 0;
            Iterator<String> iter = newData.keySet().iterator();
            while (iter.hasNext()) {
                Pattern pattern = Pattern.compile("cluster-ca-" + "([0-9T:-]{19}).(crt|key)");
                String key = iter.next();
                Matcher matcher = pattern.matcher(key);

                if (matcher.matches()) {
                    String date = matcher.group(1) + "Z";
                    Instant parse = DateTimeFormatter.ISO_DATE_TIME.parse(date, Instant::from);
                    if (parse.isBefore(Instant.now())) {
                        iter.remove();
                        removed++;
                    }
                }
            }
            return removed;
        }

        private Map<String, String> createOrRenewCert(Reconciliation reconciliation, TlsCertificates tlsCertificates, String clusterCaName, Secret clusterCa, X509Certificate currentCert) throws IOException {
            Map<String, String> newData;
            log.debug("{}: Generating cluster CA certificate {}", reconciliation, clusterCaName);
            File clusterCaKeyFile = null;
            File clusterCaCertFile = null;
            Map<String, String> data;
            try {
                clusterCaKeyFile = File.createTempFile("tls", "cluster-ca-key");
                clusterCaCertFile = File.createTempFile("tls", "cluster-ca-currentCert");

                Subject sbj = new Subject();
                sbj.setOrganizationName("io.strimzi");
                sbj.setCommonName("cluster-ca");
                certManager.generateSelfSignedCert(clusterCaKeyFile, clusterCaCertFile, sbj, ModelUtils.getCertificateValidity(tlsCertificates));

                data = new HashMap<>();
                if (currentCert != null) {
                    // Add the current certificate as an old certificate (with date in UTC)
                    String notAfterDate = DateTimeFormatter.ISO_DATE_TIME.format(currentCert.getNotAfter().toInstant().atZone(ZoneId.of("Z")));
                    data.put("cluster-ca-" + notAfterDate + ".crt", clusterCa.getData().get(CLUSTER_CA_CRT));
                    data.put("cluster-ca-" + notAfterDate + ".key", clusterCa.getData().get(CLUSTER_CA_KEY));
                }
                // Add the generated certificate as the current certificate
                data.put(CLUSTER_CA_CRT, base64Contents(clusterCaCertFile));
                data.put(CLUSTER_CA_KEY, base64Contents(clusterCaKeyFile));
            } finally {
                if (clusterCaKeyFile != null && !clusterCaKeyFile.delete()) {
                    log.debug("Unable to delete temporary file {}", clusterCaKeyFile);
                }
                if (clusterCaCertFile != null && !clusterCaCertFile.delete()) {
                    log.debug("Unable to delete temporary file {}", clusterCaKeyFile);
                }
            }
            newData = data;
            log.debug("{}: End generating cluster CA {}", reconciliation, clusterCaName);
            return newData;
        }

        private Map<String, String> checkProvidedCert(Reconciliation reconciliation, String clusterCaName, Secret clusterCa, X509Certificate currentCert, boolean needsRenewal) {
            Map<String, String> newData;
            if (needsRenewal) {
                log.warn("The {} certificate in Secret {} in namespace {} will expire on {} " +
                                "and it is not configured to automatically renew.",
                        CLUSTER_CA_CRT, clusterCaName, reconciliation.namespace(), currentCert.getNotAfter());
            } else if (clusterCa == null) {
                log.warn("The certificate (data.{}) and key (data.{}) in Secret {} in namespace {} " +
                                "need to be configured with a Base64 encoded PEM-format certificate. " +
                                "Alternatively, configure the Kafka resource with name {} in namespace {} for certificate renewal.",
                        CLUSTER_CA_CRT, CLUSTER_CA_KEY, clusterCaName, reconciliation.namespace(),
                        reconciliation.name(), reconciliation.namespace());
            }
            newData = clusterCa != null ? clusterCa.getData() : emptyMap();
            return newData;
        }

        /** The contents of the given file, base64 encoded */
        private String base64Contents(File file) throws IOException {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
        }

        private boolean certNeedsRenewal(TlsCertificates tlsCertificates, X509Certificate cert)  {
            long msTillExpired = cert.getNotAfter().getTime() - System.currentTimeMillis();
            return msTillExpired / (24L * 60L * 60L * 1000L) < ModelUtils.getRenewalDays(tlsCertificates);
        }

        private X509Certificate cert(Secret secret, String key)  {
            if (secret == null || secret.getData().get(key) == null) {
                return null;
            }
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] bytes = decoder.decode(secret.getData().get(key));
            CertificateFactory factory = null;
            try {
                factory = CertificateFactory.getInstance("X.509");
            } catch (CertificateException e) {
                throw new RuntimeException("No security provider with support for X.509 certificates", e);
            }
            try {
                Certificate certificate = factory.generateCertificate(new ByteArrayInputStream(bytes));
                if (certificate instanceof X509Certificate) {
                    return (X509Certificate) certificate;
                } else {
                    throw new RuntimeException("Certificate in key " + key + " of Secret " + secret.getMetadata().getName() + " is not an X509Certificate");
                }
            } catch (CertificateException e) {
                throw new RuntimeException("Certificate in key " + key + " of Secret " + secret.getMetadata().getName() + " could not be parsed");
            }
        }

        Future<ReconciliationState> rollingUpdateForNewCaCert() {
            if (this.clusterCaCertRenewed || this.clusterCaCertsRemoved) {
                return zkSetOperations.getAsync(namespace, ZookeeperCluster.zookeeperClusterName(name))
                        .compose(ss -> zkSetOperations.maybeRollingUpdate(ss, true))
                        .compose(i -> kafkaSetOperations.getAsync(namespace, KafkaCluster.kafkaClusterName(name)))
                        .compose(ss -> kafkaSetOperations.maybeRollingUpdate(ss, true))
                        .compose(i -> {
                            if (topicOperator != null) {
                                return deploymentOperations.rollingUpdate(namespace, TopicOperator.topicOperatorName(name), operationTimeoutMs);
                            } else {
                                return Future.succeededFuture();
                            }
                        })
                        .compose(i -> {
                            if (entityOperator != null) {
                                return deploymentOperations.rollingUpdate(namespace, EntityOperator.entityOperatorName(name), operationTimeoutMs);
                            } else {
                                return Future.succeededFuture();
                            }
                        })
                        .map(i -> this);
            } else {
                return Future.succeededFuture(this);
            }
        }


        Future<ReconciliationState> getZookeeperState() {
            Future<ReconciliationState> fut = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                future -> {
                    try {
                        this.zkCluster = ZookeeperCluster.fromCrd(certManager, kafkaAssembly, assemblySecrets);

                        ConfigMap logAndMetricsConfigMap = zkCluster.generateMetricsAndLogConfigMap(zkCluster.getLogging() instanceof ExternalLogging ?
                                configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) zkCluster.getLogging()).getName()) :
                                null);

                        this.zkService = zkCluster.generateService();
                        this.zkHeadlessService = zkCluster.generateHeadlessService();
                        this.zkMetricsAndLogsConfigMap = zkCluster.generateMetricsAndLogConfigMap(logAndMetricsConfigMap);

                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                res -> {
                    if (res.succeeded()) {
                        fut.complete((ReconciliationState) res.result());
                    } else {
                        fut.fail(res.cause());
                    }
                }
            );

            return fut;
        }

        Future<ReconciliationState> withZkDiff(Future<ReconcileResult<StatefulSet>> r) {
            return r.map(rr -> {
                this.zkDiffs = rr;
                return this;
            });
        }

        Future<ReconciliationState> withVoid(Future<?> r) {
            return r.map(this);
        }

        Future<ReconciliationState> zkScaleDown() {
            return withVoid(zkSetOperations.scaleDown(namespace, zkCluster.getName(), zkCluster.getReplicas()));
        }

        Future<ReconciliationState> zkService() {
            return withVoid(serviceOperations.reconcile(namespace, zkCluster.getServiceName(), zkService));
        }

        Future<ReconciliationState> zkHeadlessService() {
            return withVoid(serviceOperations.reconcile(namespace, zkCluster.getHeadlessServiceName(), zkHeadlessService));
        }

        Future<ReconciliationState> zkAncillaryCm() {
            return withZkAncillaryCmChanged(configMapOperations.reconcile(namespace, zkCluster.getAncillaryConfigName(), zkMetricsAndLogsConfigMap));
        }

        Future<ReconciliationState> zkNodesSecret() {
            return withVoid(secretOperations.reconcile(namespace, ZookeeperCluster.nodesSecretName(name), zkCluster.generateNodesSecret()));
        }

        Future<ReconciliationState> zkNetPolicy() {
            return withVoid(networkPolicyOperator.reconcile(namespace, ZookeeperCluster.policyName(name), zkCluster.generateNetworkPolicy()));
        }

        Future<ReconciliationState> zkStatefulSet() {
            return withZkDiff(zkSetOperations.reconcile(namespace, zkCluster.getName(), zkCluster.generateStatefulSet(isOpenShift)));
        }

        Future<ReconciliationState> zkRollingUpdate() {
            return withVoid(zkSetOperations.maybeRollingUpdate(zkDiffs.resource(), zkForcedRestart));
        }

        Future<ReconciliationState> zkScaleUp() {
            return withVoid(zkSetOperations.scaleUp(namespace, zkCluster.getName(), zkCluster.getReplicas()));
        }

        Future<ReconciliationState> zkServiceEndpointReadiness() {
            return withVoid(serviceOperations.endpointReadiness(namespace, zkService, 1_000, operationTimeoutMs));
        }

        Future<ReconciliationState> zkHeadlessServiceEndpointReadiness() {
            return withVoid(serviceOperations.endpointReadiness(namespace, zkHeadlessService, 1_000, operationTimeoutMs));
        }

        Future<ReconciliationState> withZkAncillaryCmChanged(Future<ReconcileResult<ConfigMap>> r) {
            return r.map(rr -> {
                this.zkForcedRestart = rr instanceof ReconcileResult.Patched;
                return this;
            });
        }

        private Future<ReconciliationState> getKafkaClusterDescription() {
            Future<ReconciliationState> fut = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        this.kafkaCluster = KafkaCluster.fromCrd(certManager, kafkaAssembly, assemblySecrets);

                        ConfigMap logAndMetricsConfigMap = kafkaCluster.generateMetricsAndLogConfigMap(
                                kafkaCluster.getLogging() instanceof ExternalLogging ?
                                        configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) kafkaCluster.getLogging()).getName()) :
                                        null);
                        this.kafkaService = kafkaCluster.generateService();
                        this.kafkaHeadlessService = kafkaCluster.generateHeadlessService();
                        this.kafkaMetricsAndLogsConfigMap = logAndMetricsConfigMap;

                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                res -> {
                    if (res.succeeded()) {
                        fut.complete(res.result());
                    } else {
                        fut.fail(res.cause());
                    }
                }
            );
            return fut;
        }

        Future<ReconciliationState> withKafkaDiff(Future<ReconcileResult<StatefulSet>> r) {
            return r.map(rr -> {
                this.kafkaDiffs = rr;
                return this;
            });
        }

        Future<ReconciliationState> withKafkaAncillaryCmChanged(Future<ReconcileResult<ConfigMap>> r) {
            return r.map(rr -> {
                this.kafkaForcedRestart = rr instanceof ReconcileResult.Patched;
                return this;
            });
        }

        Future<ReconciliationState> kafkaInitServiceAccount() {
            return withVoid(serviceAccountOperator.reconcile(namespace,
                    KafkaCluster.initContainerServiceAccountName(kafkaCluster.getCluster()),
                    kafkaCluster.generateInitContainerServiceAccount()));
        }

        Future<ReconciliationState> kafkaInitClusterRoleBinding() {
            return withVoid(clusterRoleBindingOperator.reconcile(
                    KafkaCluster.initContainerClusterRoleBindingName(namespace, name),
                    kafkaCluster.generateClusterRoleBinding(namespace)));
        }

        Future<ReconciliationState> kafkaScaleDown() {
            return withVoid(kafkaSetOperations.scaleDown(namespace, kafkaCluster.getName(), kafkaCluster.getReplicas()));
        }

        Future<ReconciliationState> kafkaService() {
            return withVoid(serviceOperations.reconcile(namespace, kafkaCluster.getServiceName(), kafkaService));
        }

        Future<ReconciliationState> kafkaHeadlessService() {
            return withVoid(serviceOperations.reconcile(namespace, kafkaCluster.getHeadlessServiceName(), kafkaHeadlessService));
        }

        Future<ReconciliationState> kafkaAncillaryCm() {
            return withKafkaAncillaryCmChanged(configMapOperations.reconcile(namespace, kafkaCluster.getAncillaryConfigName(), kafkaMetricsAndLogsConfigMap));
        }

        Future<ReconciliationState> kafkaClientsCaSecret() {
            return withVoid(secretOperations.reconcile(namespace, KafkaCluster.clientsCASecretName(name), kafkaCluster.generateClientsCASecret()));
        }

        Future<ReconciliationState> kafkaClientsPublicKeySecret() {
            return withVoid(secretOperations.reconcile(namespace, KafkaCluster.clientsPublicKeyName(name), kafkaCluster.generateClientsPublicKeySecret()));
        }

        Future<ReconciliationState> kafkaClusterPublicKeySecret() {
            return withVoid(secretOperations.reconcile(namespace, KafkaCluster.clusterPublicKeyName(name), kafkaCluster.generateClusterPublicKeySecret()));
        }

        Future<ReconciliationState> kafkaBrokersSecret() {
            return withVoid(secretOperations.reconcile(namespace, KafkaCluster.brokersSecretName(name), kafkaCluster.generateBrokersSecret()));
        }

        Future<ReconciliationState> kafkaNetPolicy() {
            return withVoid(networkPolicyOperator.reconcile(namespace, KafkaCluster.policyName(name), kafkaCluster.generateNetworkPolicy()));
        }

        Future<ReconciliationState> kafkaStatefulSet() {
            return withKafkaDiff(kafkaSetOperations.reconcile(namespace, kafkaCluster.getName(), kafkaCluster.generateStatefulSet(isOpenShift)));
        }

        Future<ReconciliationState> kafkaRollingUpdate() {
            return withVoid(kafkaSetOperations.maybeRollingUpdate(kafkaDiffs.resource(), kafkaForcedRestart));
        }

        Future<ReconciliationState> kafkaScaleUp() {
            return withVoid(kafkaSetOperations.scaleUp(namespace, kafkaCluster.getName(), kafkaCluster.getReplicas()));
        }

        Future<ReconciliationState> kafkaServiceEndpointReady() {
            return withVoid(serviceOperations.endpointReadiness(namespace, kafkaService, 1_000, operationTimeoutMs));
        }

        Future<ReconciliationState> kafkaHeadlessServiceEndpointReady() {
            return withVoid(serviceOperations.endpointReadiness(namespace, kafkaHeadlessService, 1_000, operationTimeoutMs));
        }

        private final Future<ReconciliationState> getTopicOperatorDescription() {
            Future<ReconciliationState> fut = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        this.topicOperator = TopicOperator.fromCrd(certManager, kafkaAssembly, assemblySecrets);

                        if (topicOperator != null) {
                            ConfigMap logAndMetricsConfigMap = topicOperator.generateMetricsAndLogConfigMap(
                                    topicOperator.getLogging() instanceof ExternalLogging ?
                                            configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) topicOperator.getLogging()).getName()) :
                                            null);
                            this.toDeployment = topicOperator.generateDeployment();
                            this.toMetricsAndLogsConfigMap = logAndMetricsConfigMap;
                            this.toDeployment.getSpec().getTemplate().getMetadata().getAnnotations().put("strimzi.io/logging", this.toMetricsAndLogsConfigMap.getData().get("log4j2.properties"));
                        } else {
                            this.toDeployment = null;
                            this.toMetricsAndLogsConfigMap = null;
                        }

                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                res -> {
                    if (res.succeeded()) {
                        fut.complete(res.result());
                    } else {
                        fut.fail(res.cause());
                    }
                }
            );
            return fut;
        }

        Future<ReconciliationState> topicOperatorServiceAccount() {
            return withVoid(serviceAccountOperator.reconcile(namespace,
                    TopicOperator.topicOperatorServiceAccountName(name),
                    toDeployment != null ? topicOperator.generateServiceAccount() : null));
        }

        Future<ReconciliationState> topicOperatorRoleBinding() {
            String watchedNamespace = topicOperator != null ? topicOperator.getWatchedNamespace() : null;
            return withVoid(roleBindingOperator.reconcile(
                    watchedNamespace != null && !watchedNamespace.isEmpty() ?
                            watchedNamespace : namespace,
                    TopicOperator.roleBindingName(name),
                    toDeployment != null ? topicOperator.generateRoleBinding(namespace) : null));
        }

        Future<ReconciliationState> topicOperatorAncillaryCm() {
            return withVoid(configMapOperations.reconcile(namespace,
                    toDeployment != null ? topicOperator.getAncillaryConfigName() : TopicOperator.metricAndLogConfigsName(name),
                    toMetricsAndLogsConfigMap));
        }

        Future<ReconciliationState> topicOperatorDeployment() {
            return withVoid(deploymentOperations.reconcile(namespace, TopicOperator.topicOperatorName(name), toDeployment));
        }

        Future<ReconciliationState> topicOperatorSecret() {
            return withVoid(secretOperations.reconcile(namespace, TopicOperator.secretName(name), topicOperator == null ? null : topicOperator.generateSecret()));
        }

        private final Future<ReconciliationState> getEntityOperatorDescription() {
            Future<ReconciliationState> fut = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        EntityOperator entityOperator = EntityOperator.fromCrd(certManager, kafkaAssembly, assemblySecrets);

                        if (entityOperator != null) {
                            EntityTopicOperator topicOperator = entityOperator.getTopicOperator();
                            EntityUserOperator userOperator = entityOperator.getUserOperator();

                            ConfigMap topicOperatorLogAndMetricsConfigMap = topicOperator != null ?
                                    topicOperator.generateMetricsAndLogConfigMap(topicOperator.getLogging() instanceof ExternalLogging ?
                                            configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) topicOperator.getLogging()).getName()) :
                                            null) : null;

                            ConfigMap userOperatorLogAndMetricsConfigMap = userOperator != null ?
                                    userOperator.generateMetricsAndLogConfigMap(userOperator.getLogging() instanceof ExternalLogging ?
                                            configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) userOperator.getLogging()).getName()) :
                                            null) : null;

                            this.entityOperator = entityOperator;
                            this.eoDeployment = entityOperator.generateDeployment();
                            this.topicOperatorMetricsAndLogsConfigMap = topicOperatorLogAndMetricsConfigMap;
                            this.userOperatorMetricsAndLogsConfigMap = userOperatorLogAndMetricsConfigMap;
                        }

                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                res -> {
                    if (res.succeeded()) {
                        fut.complete(res.result());
                    } else {
                        fut.fail(res.cause());
                    }
                }
            );
            return fut;
        }

        Future<ReconciliationState> entityOperatorServiceAccount() {
            return withVoid(serviceAccountOperator.reconcile(namespace,
                    EntityOperator.entityOperatorServiceAccountName(name),
                    eoDeployment != null ? entityOperator.generateServiceAccount() : null));
        }

        Future<ReconciliationState> entityOperatorTopicOpRoleBinding() {
            String watchedNamespace = entityOperator != null && entityOperator.getTopicOperator() != null ?
                    entityOperator.getTopicOperator().getWatchedNamespace() : null;
            return withVoid(roleBindingOperator.reconcile(
                    watchedNamespace != null && !watchedNamespace.isEmpty() ?
                            watchedNamespace : namespace,
                    EntityTopicOperator.roleBindingName(name),
                    eoDeployment != null && entityOperator.getTopicOperator() != null ?
                            entityOperator.getTopicOperator().generateRoleBinding(namespace) : null));
        }

        Future<ReconciliationState> entityOperatorUserOpRoleBinding() {
            String watchedNamespace = entityOperator != null && entityOperator.getUserOperator() != null ?
                    entityOperator.getUserOperator().getWatchedNamespace() : null;
            return withVoid(roleBindingOperator.reconcile(
                    watchedNamespace != null && !watchedNamespace.isEmpty() ?
                            watchedNamespace : namespace,
                    EntityUserOperator.roleBindingName(name),
                    eoDeployment != null && entityOperator.getUserOperator() != null ?
                            entityOperator.getUserOperator().generateRoleBinding(namespace) : null));
        }

        Future<ReconciliationState> entityOperatorTopicOpAncillaryCm() {
            return withVoid(configMapOperations.reconcile(namespace,
                    eoDeployment != null && entityOperator.getTopicOperator() != null ?
                            entityOperator.getTopicOperator().getAncillaryConfigName() : EntityTopicOperator.metricAndLogConfigsName(name),
                    topicOperatorMetricsAndLogsConfigMap));
        }

        Future<ReconciliationState> entityOperatorUserOpAncillaryCm() {
            return withVoid(configMapOperations.reconcile(namespace,
                    eoDeployment != null && entityOperator.getUserOperator() != null ?
                            entityOperator.getUserOperator().getAncillaryConfigName() : EntityUserOperator.metricAndLogConfigsName(name),
                    userOperatorMetricsAndLogsConfigMap));
        }

        Future<ReconciliationState> entityOperatorDeployment() {
            return withVoid(deploymentOperations.reconcile(namespace, EntityOperator.entityOperatorName(name), eoDeployment));
        }

        Future<ReconciliationState> entityOperatorSecret() {
            return withVoid(secretOperations.reconcile(namespace, EntityOperator.secretName(name), entityOperator == null ? null : entityOperator.generateSecret()));
        }

    }

    private final Future<CompositeFuture> deleteKafka(Reconciliation reconciliation) {
        String namespace = reconciliation.namespace();
        String name = reconciliation.name();
        log.debug("{}: delete kafka {}", reconciliation, name);
        String kafkaSsName = KafkaCluster.kafkaClusterName(name);
        StatefulSet ss = kafkaSetOperations.get(namespace, kafkaSsName);
        boolean deleteClaims = ss == null ? false : KafkaCluster.deleteClaim(ss);
        List<Future> result = new ArrayList<>(8 + (deleteClaims ? ss.getSpec().getReplicas() : 0));

        result.add(configMapOperations.reconcile(namespace, KafkaCluster.metricAndLogConfigsName(name), null));
        result.add(serviceOperations.reconcile(namespace, KafkaCluster.serviceName(name), null));
        result.add(serviceOperations.reconcile(namespace, KafkaCluster.headlessServiceName(name), null));
        result.add(kafkaSetOperations.reconcile(namespace, KafkaCluster.kafkaClusterName(name), null));
        result.add(secretOperations.reconcile(namespace, KafkaCluster.clientsCASecretName(name), null));
        result.add(secretOperations.reconcile(namespace, KafkaCluster.clientsPublicKeyName(name), null));
        result.add(secretOperations.reconcile(namespace, KafkaCluster.clusterPublicKeyName(name), null));
        result.add(secretOperations.reconcile(namespace, KafkaCluster.brokersSecretName(name), null));
        result.add(networkPolicyOperator.reconcile(namespace, KafkaCluster.policyName(name), null));

        if (deleteClaims) {
            log.debug("{}: delete kafka {} PVCs", reconciliation, name);

            for (int i = 0; i < ss.getSpec().getReplicas(); i++) {
                result.add(pvcOperations.reconcile(namespace,
                        KafkaCluster.getPersistentVolumeClaimName(kafkaSsName, i), null));
            }
        }
        result.add(clusterRoleBindingOperator.reconcile(KafkaCluster.initContainerClusterRoleBindingName(namespace, name), null));
        result.add(serviceAccountOperator.reconcile(namespace, KafkaCluster.initContainerServiceAccountName(name), null));
        return CompositeFuture.join(result);
    }

    private final Future<CompositeFuture> deleteZk(Reconciliation reconciliation) {
        String namespace = reconciliation.namespace();
        String name = reconciliation.name();
        log.debug("{}: delete zookeeper {}", reconciliation, name);
        String zkSsName = ZookeeperCluster.zookeeperClusterName(name);
        StatefulSet ss = zkSetOperations.get(namespace, zkSsName);
        boolean deleteClaims = ss == null ? false : ZookeeperCluster.deleteClaim(ss);
        List<Future> result = new ArrayList<>(4 + (deleteClaims ? ss.getSpec().getReplicas() : 0));

        result.add(configMapOperations.reconcile(namespace, ZookeeperCluster.zookeeperMetricAndLogConfigsName(name), null));
        result.add(serviceOperations.reconcile(namespace, ZookeeperCluster.serviceName(name), null));
        result.add(serviceOperations.reconcile(namespace, ZookeeperCluster.headlessServiceName(name), null));
        result.add(zkSetOperations.reconcile(namespace, zkSsName, null));
        result.add(secretOperations.reconcile(namespace, ZookeeperCluster.nodesSecretName(name), null));
        result.add(networkPolicyOperator.reconcile(namespace, ZookeeperCluster.policyName(name), null));

        if (deleteClaims) {
            log.debug("{}: delete zookeeper {} PVCs", reconciliation, name);
            for (int i = 0; i < ss.getSpec().getReplicas(); i++) {
                result.add(pvcOperations.reconcile(namespace, ZookeeperCluster.getPersistentVolumeClaimName(zkSsName, i), null));
            }
        }

        return CompositeFuture.join(result);
    }



    private final Future<CompositeFuture> deleteTopicOperator(Reconciliation reconciliation) {
        String namespace = reconciliation.namespace();
        String name = reconciliation.name();
        log.debug("{}: delete topic operator {}", reconciliation, name);

        List<Future> result = new ArrayList<>(3);
        result.add(configMapOperations.reconcile(namespace, TopicOperator.metricAndLogConfigsName(name), null));
        result.add(deploymentOperations.reconcile(namespace, TopicOperator.topicOperatorName(name), null));
        result.add(secretOperations.reconcile(namespace, TopicOperator.secretName(name), null));
        result.add(roleBindingOperator.reconcile(namespace, TopicOperator.roleBindingName(name), null));
        result.add(serviceAccountOperator.reconcile(namespace, TopicOperator.topicOperatorServiceAccountName(name), null));
        return CompositeFuture.join(result);
    }

    private final Future<CompositeFuture> deleteEntityOperator(Reconciliation reconciliation) {
        String namespace = reconciliation.namespace();
        String name = reconciliation.name();
        log.debug("{}: delete entity operator {}", reconciliation, name);

        List<Future> result = new ArrayList<>(3);
        result.add(configMapOperations.reconcile(namespace, EntityTopicOperator.metricAndLogConfigsName(name), null));
        result.add(configMapOperations.reconcile(namespace, EntityUserOperator.metricAndLogConfigsName(name), null));
        result.add(deploymentOperations.reconcile(namespace, EntityOperator.entityOperatorName(name), null));
        result.add(secretOperations.reconcile(namespace, EntityOperator.secretName(name), null));
        result.add(roleBindingOperator.reconcile(namespace, EntityTopicOperator.roleBindingName(name), null));
        result.add(roleBindingOperator.reconcile(namespace, EntityUserOperator.roleBindingName(name), null));
        result.add(serviceAccountOperator.reconcile(namespace, EntityOperator.entityOperatorServiceAccountName(name), null));
        return CompositeFuture.join(result);
    }

    @Override
    protected Future<Void> delete(Reconciliation reconciliation) {
        return deleteEntityOperator(reconciliation)
                .compose(i -> deleteTopicOperator(reconciliation))
                .compose(i -> deleteKafka(reconciliation))
                .compose(i -> deleteZk(reconciliation))
                .compose(i -> deleteClusterCa(reconciliation));
    }

    private final Future<Void> deleteClusterCa(Reconciliation reconciliation) {
        Future<Void> result = Future.future();
        vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
            future -> {
                String clusterCaName = AbstractModel.getClusterCaName(reconciliation.name());

                if (secretOperations.get(reconciliation.namespace(), clusterCaName) != null) {
                    log.debug("{}: Deleting cluster CA certificate {}", reconciliation, clusterCaName);
                    secretOperations.reconcile(reconciliation.namespace(), clusterCaName, null)
                            .compose(reconcileResult -> future.complete(), future);
                    log.debug("{}: Cluster CA {} deleted", reconciliation, clusterCaName);
                } else {
                    log.debug("{}: The cluster CA {} doesn't exist", reconciliation, clusterCaName);
                    future.complete();
                }
            }, true,
            result.completer()
        );
        return result;
    }

    @Override
    protected List<HasMetadata> getResources(String namespace, Labels selector) {
        List<HasMetadata> result = new ArrayList<>();
        result.addAll(kafkaSetOperations.list(namespace, selector));
        result.addAll(zkSetOperations.list(namespace, selector));
        result.addAll(deploymentOperations.list(namespace, selector));
        result.addAll(serviceOperations.list(namespace, selector));
        result.addAll(resourceOperator.list(namespace, selector));
        return result;
    }
}