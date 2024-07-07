public class Worker {

    //-------------------------Fields-------------------------//
    
    private static AWS aws = AWS.getInstance();
    private final static String instanceId = aws.getInstanceID();
    private static sentimentAnalysisHandler sentimentAnalysisHandler = new sentimentAnalysisHandler();
    private static namedEntityRecognitionHandler namedEntityRecognitionHandler = new namedEntityRecognitionHandler();

    //---------------------MAIN function---------------------//

    public static void main(String[] args){
        
        System.out.println("[DEBUG] Worker started");
        
        while (true) {    
            //STEP 1: get a message from Maneger.
            
            String[] message_info = aws.recieveMessage();
                
            if(message_info[0] != null && message_info[1] != null){  
                    
                //extract relevent data from message.
                String[] messageContent = message_info[0].split("\\|\\|\\|");
                String id = messageContent[0].split("\\$\\$")[0];
                String n = messageContent[0].split("\\$\\$")[1];
                aws.changeVisibilityTimeout(message_info[1], Integer.parseInt(n));
                System.out.println("[DEBUG] contect of message: " + messageContent);
                System.out.println("[DEBUG] Worker get id(BucketName+fileIndex): " + id);
                    
                //STEP 2: build analyzed respond.
                StringBuilder accAns = new StringBuilder();
                    
                for(int i = 1; i < messageContent.length; i++){
                        
                    String[] review = messageContent[i].split(" \\$\\$\\$ ");
                        
                    if (review.length == 3) {
                        //extract details.
                        String link = review[0];
                        String text = review[1];
                        int rating = Integer.parseInt(review[2]);
                        System.out.println("--------------------------------------------------------");
                        System.out.printf("link: %s\ntext: %s\nrating: %d\n", link, text, rating);
                        System.out.println("--------------------------------------------------------");
                            
                        //analyze the review.
                        String entities = namedEntityRecognitionHandler.findEntities(text);
                        System.out.printf("[DEBUG] Entities: " + entities);
                        int newRating = sentimentAnalysisHandler.findSentiment(text);
                        System.out.printf("[DEBUG] new rating: ", newRating);
                        Review reviewObj = new Review(link, rating, newRating, entities);
                        accAns.append(reviewObj.toString()).append("\n");
                            
                    } else {
                        // Handle the case where revStr does not have enough elements
                        System.out.println("Invalid format: " + messageContent[i]);
                    }
                }
                String msgToManager = instanceId + "|||" + id + "|||" + accAns.toString();
                System.out.println("[DEBUG] message to manager: " + msgToManager);

                //STEP 3: send the respond to Manager.
                aws.sendMessage(msgToManager);
                aws.deleteMessages(message_info[1]);
            }
            else{
                try{
                    Thread.sleep(5000);
                } catch(Exception e){
                    System.out.println("Woke up");
                }
            }
        }
    }
}
