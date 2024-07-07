import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import java.util.Base64;
import java.util.List;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class AWS {

//-------------------------Fields-------------------------//

    final S3Client s3;
    final SqsClient sqs;
    final Ec2Client ec2;

    public static String ami = "ami-00e95a9222311e8ed";
    public static String workerScript = "#!/bin/bash\n" + 
                                        "yum update -y\n" + 
                                        "yum install -y aws-cli\n" + 
                                        "aws s3 cp s3://bucket15032000/Worker.jar /home/Worker.jar\n" + 
                                        "java -Xms4g -Xmx4g -jar /home/Worker.jar"; 

    public static Region region1 = Region.US_WEST_2;
    public static Region region2 = Region.US_EAST_1;

    private static final AWS instance = new AWS();

    private Integer WorkersCounter = 0;
    private final Integer MAX_WORKERS = 6;

//----------------------Constructor-----------------------//

    private AWS() {
        s3 = S3Client.builder().region(region1).build();
        sqs = SqsClient.builder().region(region1).build();
        ec2 = Ec2Client.builder().region(region1).build();
    }

//-------------------------Methods------------------------//

    public static AWS getInstance() {
        return instance;
    }


    //====================== S3 ======================//

    public BufferedReader downloadFile(String bucketName, String key){
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(key)
            .build();
        try{
            ResponseInputStream<GetObjectResponse> inputStream = s3.getObject(getObjectRequest);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            return reader;
        } catch(Exception e){
            System.err.printf("Error downloading file from S3: %s\n", e.getMessage());
            throw e;
        }

    } 

    public void uploadFileToS3(String bucketName, String key, String content){
        s3.putObject(PutObjectRequest.builder().bucket(bucketName).key(key).build(), RequestBody.fromString(content));
    }

    //====================== EC2 ======================//

    public void terminateManager() { // For the Manager
        Ec2Client ec2 = Ec2Client.builder().region(region2).build();
        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                                                                    .filters(Filter.builder()
                                                                                    .name("tag:" + "Name")
                                                                                    .values("Manager")
                                                                                    .build())
                                                                    .build();
        DescribeInstancesResponse response = ec2.describeInstances(request);
        try{
            for(Reservation r : response.reservations()){
                for(Instance instance : r.instances()){
                    ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instance.instanceId()).build());
                    System.out.println("terminate Manager");
                }
            }
        } catch (Exception e){
            e.printStackTrace();
        }

        // List<Reservation> reservations = ec2.describeInstances().reservations();
        // for (Reservation reservation : reservations) {
        //     Instance instance = reservation.instances().get(0);
        //     if (!instance.tags().isEmpty()) {
        //         if((instance.tags().get(1).value().equals("Manager")|| instance.tags().get(0).value().equals("Manager")) && (instance.state().nameAsString().equals("running"))) {
        //             ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instance.instanceId()).build());
        //             System.out.println("terminate Manager");
        //         }
        //     }
        // }
    }

    public void terminateWorkers(List<String> freeWorker_list){
        for(String instanceId : freeWorker_list){
            terminateInstance(instanceId);
        }
        
    }

    public void terminateInstance(String instanceId){
        Ec2Client ec2 = Ec2Client.builder().region(region2).build();
        try{
            TerminateInstancesRequest request = TerminateInstancesRequest.builder()
                .instanceIds(instanceId)
                .build();
            ec2.terminateInstances(request);
            System.out.printf("[DEBUG] AWS - MANAGER - terminated instance - instance ID %s\n", instanceId);

        } catch (Exception e){
            e.printStackTrace();
        }
    }

    private String createEC2(String script, String tagName, int numberOfInstances) {
        Ec2Client ec2 = Ec2Client.builder().region(region2).build();
        RunInstancesRequest runRequest = (RunInstancesRequest) RunInstancesRequest.builder()
                .instanceType(InstanceType.M4_LARGE)
                .imageId(ami)
                .maxCount(numberOfInstances)
                .minCount(1)
                .keyName("vockey")
                .iamInstanceProfile(IamInstanceProfileSpecification.builder().name("LabInstanceProfile").build())
                .userData(Base64.getEncoder().encodeToString((script).getBytes()))
                .build();


        RunInstancesResponse response = ec2.runInstances(runRequest);

        String instanceId = response.instances().get(0).instanceId();

        software.amazon.awssdk.services.ec2.model.Tag tag = Tag.builder()
                .key("Name")
                .value(tagName)
                .build();

        CreateTagsRequest tagRequest = (CreateTagsRequest) CreateTagsRequest.builder()
                .resources(instanceId)
                .tags(tag)
                .build();

        try {
            ec2.createTags(tagRequest);
            System.out.printf(
                    "[DEBUG] Successfully started EC2 instance %s based on AMI %s\n",
                    instanceId, ami);

        } catch (Ec2Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
        }
        return instanceId;
    }

    public void createWorkers(String tagName, int numberOfInstances) {
        if(numberOfInstances == 0) return;
        
        synchronized(WorkersCounter){
            if(WorkersCounter == MAX_WORKERS){
                return;
            }

            Integer finalWorkers = Math.min(numberOfInstances + WorkersCounter.intValue(), MAX_WORKERS.intValue());
            int nodeCount = finalWorkers.intValue() - WorkersCounter.intValue();
            System.out.printf("[DEBUG] AWS - Manager - create ec2 - %d\n", nodeCount);
            WorkersCounter = finalWorkers;
            System.out.printf("[DEBUG] AWS - Manager - WorkersCounter - %d\n", WorkersCounter.intValue());

            for(int i = 0; i < nodeCount; i++){
                createEC2(workerScript, tagName, 1);
            }
        }
        return;
    }


    public int workersAmount(){
        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                                                                    .filters(Filter.builder()
                                                                                    .name("tag:" + "Name")
                                                                                    .values("worker")
                                                                                    .build())
                                                                    .build();
        DescribeInstancesResponse response = ec2.describeInstances(request);
        return response.reservations().size(); 
    }

    //====================== SQS ======================//

    public String createSqsQueue(String queueName) {
        CreateQueueRequest createQueueRequest = CreateQueueRequest.builder()
                                                                .queueName(queueName)
                                                                .build();
        sqs.createQueue(createQueueRequest);
        GetQueueUrlResponse queueUrlResponse = sqs.getQueueUrl(GetQueueUrlRequest.builder()
                                                                                .queueName(queueName)
                                                                                .build()
        );
        return queueUrlResponse.queueUrl();
    }


    public String getQueueUrl(String queueName){
        while (true) {
            try {
                // Get the URL of the SQS queue by tag
                GetQueueUrlResponse queueUrlResponse = sqs.getQueueUrl(GetQueueUrlRequest.builder()
                                                                                    .queueName(queueName)
                                                                                    .build()
                );
                // If the queue exists, print its URL and break out of the loop
                System.out.println("Queue URL: " + queueUrlResponse.queueUrl());
                return queueUrlResponse.queueUrl();
            } catch (QueueDoesNotExistException e) {
                // If the queue does not exist, handle the exception
                System.out.println("Queue with tagdoes not exist. Retrying...");
            }

            // Wait for a specified interval before checking again
            try {
                Thread.sleep(5000); // Adjust the interval as needed (in milliseconds)
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    public void sendMessage(String msg, String sqsURL){ 
        SendMessageRequest send_msg_request = SendMessageRequest.builder()
                                                                .queueUrl(sqsURL)
                                                                .messageBody(msg)
                                                                .delaySeconds(5)
                                                                .build();
        sqs.sendMessage(send_msg_request);
    }
    

    public List<Message> receiveMessages(String queueUrl){
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                                                                    .queueUrl(queueUrl)
                                                                    .build();
        return sqs.receiveMessage(receiveRequest).messages();
    }


    public void deleteMessages(String queueUrl, String mesReceipt){
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(mesReceipt)
                        .build();
        sqs.deleteMessage(deleteRequest);
    }


    public void deleteSqs(String sqsUrl){
        sqs.deleteQueue(DeleteQueueRequest.builder()
                                        .queueUrl(sqsUrl)
                                        .build());
    }


}
