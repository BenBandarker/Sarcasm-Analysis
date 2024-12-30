import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

public class AWS {

//----------------------Fields----------------------//

    final S3Client s3;
    final SqsClient sqs;
    final Ec2Client ec2;

    public static String ami = "ami-003d4401fea2d2d0f";

    public static String ManagerScript = "#!/bin/bash\n" + 
                                            "yum update -y\n" +
                                            "yum install -y aws-cli\n" +
                                            "aws s3 cp s3://bucket15032000/Manager.jar /home/Manager.jar\n" + 
                                            "java -cp /home/Manager.jar  Manager\n";

    public static Region region1 = Region.US_WEST_2;

    private static final AWS instance = new AWS();

//---------------------Constructor-------------------//
    
private AWS() {
    s3 = S3Client.builder().region(region1).build();
    sqs = SqsClient.builder().region(region1).build();
    ec2 = Ec2Client.builder().region(region1).build();
}

//----------------------Methods----------------------//
    
public static AWS getInstance() {
    return instance;
}

//====================== S3 ======================//

public void createBucketIfNotExists(String bucketName) {
    try {
        s3.createBucket(CreateBucketRequest
                .builder()
                .bucket(bucketName)
                .createBucketConfiguration(
                        CreateBucketConfiguration.builder()
                                .locationConstraint(BucketLocationConstraint.US_WEST_2)
                                .build())
                .build());
        s3.waiter().waitUntilBucketExists(HeadBucketRequest.builder()
                .bucket(bucketName)
                .build());
        System.out.printf("[DEBUG] LocalApp - bucket created - %s\n", bucketName);
    } catch (S3Exception e) {
        System.out.println(e.getMessage());
    }
}

    public void getFileFromS3(String bucketName, String key, String location){
        // get the file from S3 and save it in "location"
        System.out.println("[DEBUG] LocalApp - s3- getting object " + key + " from bucket " + bucketName);
        try{
            s3.getObject(GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build(),
            ResponseTransformer.toFile(Paths.get(location)));
            System.out.printf("[DEBUG] LocalApp - Got file from bucket - %s, Key - %s, To Location - %s\n", bucketName, key, location);
        }catch(Exception e){
            System.err.println("Error in downloading file: " + e.getMessage());
        }
        
    }

    public void deleteObject(String bucketName,String objectKey){
        DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                                                        .bucket(bucketName)
                                                        .key(objectKey)
                                                        .build();
        s3.deleteObject(deleteObjectRequest);
        System.out.printf("[DEBUG] LocalApp - Deleted object from bucket - %s, Key - %s\n", bucketName, objectKey);
    }

    public void deleteBucket(String bucket) {
        DeleteBucketRequest deleteBucketRequest = DeleteBucketRequest.builder()
                                                    .bucket(bucket)
                                                    .build();
        s3.deleteBucket(deleteBucketRequest);
        System.out.printf("[DEBUG] LocalApp - Deleted bucket - %s\n", bucket);
    }

    public void uploadFileToS3(String bucketName, String key, String location){
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .build(),Paths.get(location));
        System.out.printf("[DEBUG] LocalApp - Uploaded file to bucket - %s, Key - %s, From Location - %s\n", bucketName, key, location);
    }

    //====================== EC2 ======================//

    private String createEC2(String script, String tagName, int numberOfInstances) {
        //Ec2Client ec2 = Ec2Client.builder().region(region2).build();
        RunInstancesRequest runRequest = (RunInstancesRequest) RunInstancesRequest.builder()
            .instanceType(InstanceType.M5_LARGE)
            .imageId(ami)
            .maxCount(numberOfInstances)
            .minCount(1)
            .iamInstanceProfile(iamInstanceProfile -> iamInstanceProfile.name("BenEC2Role"))
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
    
    public void startManagerIfNotActive() {

        // // Describe instances with the specified tag
        // DescribeInstancesResponse response = ec2.describeInstances(
        //         DescribeInstancesRequest.builder().filters(
        //                 Filter.builder()
        //                         .name("tag:" + "Name")
        //                         .values("Manager")
        //                         .build()
        //         ).build()
        // );

        // Check if any instances were found
        if(!isManagerActive()){
            createEC2(ManagerScript, "Manager", 1);
            System.out.printf("[DEBUG] LocalApp - Manager created\n");
        }
    }

    public boolean isManagerActive(){
        DescribeInstancesRequest request = DescribeInstancesRequest.builder().build();
        DescribeInstancesResponse response = ec2.describeInstances(request);
        for (Reservation reservation : response.reservations()) {
            for (Instance instance : reservation.instances()) {
                for(Tag tag : instance.tags()){
                    if((instance.state().name()==InstanceStateName.RUNNING || instance.state().name()==InstanceStateName.PENDING) && tag.key().equals("Name") && tag.value().equals("MANAGER")){
                        return true;
                    }
                }
            }
        }
        return false;
    }

    //====================== SQS ======================//

    public void deleteMessages(String sqsUrl){
        List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(sqsUrl).build()).messages();
        // delete messages from the queue
        for (Message m : messages) {
            String str = m.receiptHandle();
           DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                 .queueUrl(sqsUrl)
                 .receiptHandle(m.receiptHandle())
                 .build();
            sqs.deleteMessage(deleteRequest);
            System.out.printf("[DEBUG] LocalApp - Deleted message from Queue - %s, Message Reciept - %s, To Location - %s\n", sqsUrl, str);
        }
    }

    public void deleteSqs(String sqsUrl){
        // Delete the queue
        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(sqsUrl).build());
        System.out.printf("[DEBUG] LocalApp - Queue Deleted - URL - %s\n", sqsUrl);
    }

    public String getQueueUrl(String queueName){
        while (true) {
            try {
                // Get the URL of the SQS queue by tag
                GetQueueUrlResponse queueUrlResponse = sqs.getQueueUrl(
                        GetQueueUrlRequest.builder().queueName(queueName).build()
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
                Thread.sleep(10000); // Adjust the interval as needed (in milliseconds)
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void sendMessage(String str, String sqsURL){ //Notify the manager.
        SendMessageRequest send_msg_request = SendMessageRequest.builder()
                .queueUrl(sqsURL)
                .messageBody(str)
                .delaySeconds(5)
                .build();
        sqs.sendMessage(send_msg_request);
        System.out.printf("[DEBUG] LocalApp - Send message to Queue - %s, Message - %s\n", sqsURL, str);


    } 

    public String recieveMessage(String sqsUrl){
        String ans = "";
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                    .queueUrl(sqsUrl)
                    .visibilityTimeout(120)
                    .build();
        List<Message> messages = sqs.receiveMessage(receiveRequest).messages();
        if(messages.size() > 0){
            ans = messages.get(0).body();
            System.out.printf("[DEBUG] LocalApp - Got message from Queue - %s, Message - %s\n", sqsUrl, ans);
            //erase the message from the queue.
            DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                        .queueUrl(sqsUrl)
                        .receiptHandle(messages.get(0).receiptHandle())
                        .build();
            sqs.deleteMessage(deleteRequest);
            System.out.printf("[DEBUG] LocalApp - Deleted message from Queue - %s, Message - %s\n", sqsUrl, ans);
        }
        
        return ans;
    }
}
