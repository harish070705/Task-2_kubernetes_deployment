package com.example.taskrunner.service;

import com.example.taskrunner.model.TaskExecution;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import java.time.Instant;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service("k8sExecutor")
@Profile("k8s")
public class K8sJobExecutorService implements ExecutorService {

        private final KubernetesClient client;

        @Value("${task.k8s.namespace:default}")
        private String namespace;

        public K8sJobExecutorService(KubernetesClient client) {
                this.client = client;
        }

        @Override
        public TaskExecution execute(String command) throws Exception {
                Instant start = Instant.now();
                String jobName = "task-run-" + UUID.randomUUID().toString().substring(0, 8);

                // 1. Define the Job
                final Job job = new JobBuilder()
                                .withApiVersion("batch/v1")
                                .withNewMetadata()
                                .withName(jobName)
                                .withNamespace(namespace)
                                .endMetadata()
                                .withNewSpec()
                                .withNewTemplate()
                                .withNewSpec()
                                .withContainers(new ContainerBuilder()
                                                .withName(jobName)
                                                .withImage("busybox") // Use busybox image as requested
                                                .withCommand("sh", "-c", command)
                                                .build())
                                .withRestartPolicy("Never")
                                .endSpec()
                                .endTemplate()
                                .withBackoffLimit(0) // Don't retry on failure
                                .endSpec()
                                .build();

                // 2. Create the Job
                client.batch().v1().jobs().inNamespace(namespace).create(job);

                // 3. Wait for the Job to complete
                client.batch().v1().jobs().inNamespace(namespace).withName(jobName)
                                .waitUntilCondition(j -> j != null && j.getStatus() != null &&
                                                (j.getStatus().getSucceeded() != null
                                                                && j.getStatus().getSucceeded() > 0 ||
                                                                j.getStatus().getFailed() != null
                                                                                && j.getStatus().getFailed() > 0),
                                                5, TimeUnit.MINUTES); // 5 minute timeout

                // 4. Get the logs from the pod created by the Job
                String podName = client.pods().inNamespace(namespace)
                                .withLabel("job-name", jobName)
                                .list().getItems().get(0).getMetadata().getName();

                String output = client.pods().inNamespace(namespace)
                                .withName(podName).getLog();

                Instant end = Instant.now();

                // 5. Clean up the Job
                client.batch().v1().jobs().inNamespace(namespace).withName(jobName).delete();

                return new TaskExecution(start, end, output);
        }
}