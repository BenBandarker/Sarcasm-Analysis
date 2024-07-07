import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class AWS {

//-------------------------Fields------------------------//

    //URL_MW is the url of the MANAGER_TO_WORKER queue.
    private static String URL_MW = "";
    
    //URL_WM is the url of the WORKER_TO_MANAGER queue.
    private static String URL_WM = "";
    
    final SqsClient sqs = SqsClient.builder().region(region1).build();
    
    public static Region region1 = Region.US_WEST_2;
    public static Region region2 = Region.US_EAST_1;

    private static final AWS instance = new AWS();

//----------------------Constructor-----------------------//

    private AWS() {
        //PART 1: connect to MANAGER_TO_WORKER queue. 
        URL_MW = connentToQueue("MANAGER_TO_WORKER", URL_MW);
        //PART 2: connect to WORKER_TO_MANAGER queue.
        URL_WM = connentToQueue("WORKER_TO_MANAGER", URL_WM);
    }

//-------------------------Methods------------------------//

    public static AWS getInstance() {
        return instance;
    }

    @SuppressWarnings("deprecation")
    public String getInstanceID(){
        String instanceId = "";
        try {
            // Create a URL object for the metadata endpoint
            URL url = new URL("http://169.254.169.254/latest/meta-data/instance-id");

            // Open a connection to the metadata service
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            // Read the response from the metadata service
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            instanceId = reader.readLine(); // Read the instance ID from the response

            // Close resources
            reader.close();
            connection.disconnect();

        } catch (IOException e) {
            e.printStackTrace();
        }
        return instanceId;
    }

    
    //======================== SQS =========================//
    
    public String connentToQueue(String queueName, String URL){
        try {
            // Get the URL_MW of the SQS queue by tag.
            GetQueueUrlResponse url = sqs.getQueueUrl(GetQueueUrlRequest
                                                            .builder()
                                                            .queueName(queueName) //"MANAGER_TO_WORKER"
                                                            .build()
            );
            URL = url.queueUrl();  //URL_MW
            System.out.println("[DEBUG] Successfully got the URL " + queueName);
        } catch (QueueDoesNotExistException e) {
            // If the queue does not exist, handle the exception.
            System.out.println("[ERROR] Queue with tag: " + queueName + " does not exist. Retrying...");
        }
        return URL;
    }


    public String[] recieveMessage(){
        String[] ans = new String[2];
        try{
            ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                                                        .queueUrl(URL_MW)
                                                        .maxNumberOfMessages(1)
                                                        .visibilityTimeout(360)
                                                        .build();
            List<Message> messages = sqs.receiveMessage(receiveRequest).messages();
            
            if(messages.size() > 0){
                Message msg = messages.get(0);
                ans[0] = msg.body();
                ans[1] = msg.receiptHandle();
                System.out.printf("[DEBUG] SQS - recieved message from MANAGER_TO_WORKER - %s, RECIEPT - %s\n", ans[0], ans[1]);
            }
            
        }catch (Exception e){
            // Print the cause of the exception
            Throwable cause = e.getCause();
            if (cause != null) {
                System.err.println("Cause: " + cause.getMessage());
            }
        }
        return ans;
    }

    public void changeVisibilityTimeout(String receipt, int n) {
    
        ChangeMessageVisibilityRequest request = ChangeMessageVisibilityRequest.builder()
            .queueUrl(URL_MW) 
            .receiptHandle(receipt)
            .visibilityTimeout(300 * n)
            .build(); 

        sqs.changeMessageVisibility(request);
        System.out.println("[DEBUG] SQS - Changed visibility timeout of MANAGER_TO_WORKER");
    }

    public void deleteMessages(String receipt){
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                                                .queueUrl(URL_MW)
                                                .receiptHandle(receipt)
                                                .build();
        sqs.deleteMessage(deleteRequest);
        System.out.println("[DEBUG] SQS - Deleted message from MANAGER_TO_WORKER");
    }

    
    public void sendMessage(String str){ 
        SendMessageRequest send_msg_request = SendMessageRequest.builder()
                                                .queueUrl(URL_WM)
                                                .messageBody(str)
                                                .delaySeconds(5)
                                                .build();
        sqs.sendMessage(send_msg_request);
        System.out.printf("[DEBUG] SQS - Sent message to WORKER_TO_MANAGER - %s\n", str);
    }    
}

