import java.io.IOException;
import java.util.UUID;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;  

public class LocalApp{  

//----------------------------Fields-----------------------------//
    
    final static AWS aws = AWS.getInstance();

    //URL_AM is the url of the APP_TO_MANAGER queue.
    private static String URL_AM = "";

    //URL_UK is the url of the UNIQUE_KEY(MANAGER_TO_APP) queue.
    private static String URL_UK = "";

    //UNIQUE_KEY is an unique number given to local application.
    private static String UNIQUE_KEY = UUID.randomUUID().toString();

    //TerminateFlag indicades if the program got a terminate request.
    private static boolean terminateFlag = false;
    
//-------------------------MAIN function-------------------------//
    public static void main(String[] args){
        if(args.length > 2){
                  
            //STEP 1: Update terminateFlag.
            if(((args.length) % 2 == 0 ) && args[args.length -1].equals("terminate")){
                terminateFlag = true;
            }
            //STEP 2: Start the manager if not exist.       
            aws.startManagerIfNotActive();

            //STEP 3: Create a bucket in S3 and upload the input files to it.
            aws.createBucketIfNotExists(UNIQUE_KEY);

            int numOfInputFiles = (args.length - 1)/2;

            for(int key = 0 ; key  < numOfInputFiles ; key++){  
                aws.uploadFileToS3(UNIQUE_KEY, Integer.toString(key), args[key]);
            }

            //STEP 4: Waiting for the manager to create APP_TO_MANAGER queue, and get URL_AM.
            URL_AM = aws.getQueueUrl("APP_TO_MANAGER");

            //STEP 5: Write a message for manager and sent it through APP_TO_MANAGER queue.  
            String n = "";
            if(terminateFlag){
                n = args[args.length - 2];
            }
            else{
                n = args[args.length - 1];
            }

            String msg = UNIQUE_KEY + "|||" + n + "|||" + '0' + "|||" + (numOfInputFiles - 1);
            System.out.println("[DEBUG] message sent: " + msg);
            aws.sendMessage(msg, URL_AM);
            
            //STEP 6: Waiting for the manager to create UNIQUE_KEY queue, and get the response.
            URL_UK = aws.getQueueUrl(UNIQUE_KEY);
            
            String answer = aws.recieveMessage(URL_UK);
            while (!answer.equals("DONE")) { 
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                URL_UK = aws.getQueueUrl(UNIQUE_KEY);
                answer = aws.recieveMessage(URL_UK);
            }

            //STEP 7: Get the results from S3 and creates html files accordingly.
            int index = numOfInputFiles;
            for(int key = 0; index < args.length && key < numOfInputFiles; key++ , index++){
                String path = args[index].toString();
                String res_file_index =  UNIQUE_KEY + "file" + Integer.toString(key);
                aws.getFileFromS3(UNIQUE_KEY, res_file_index, path);
                aws.deleteObject(UNIQUE_KEY, Integer.toString(key));
                aws.deleteObject(UNIQUE_KEY, res_file_index);
                convertTextToHtml(path);
            }

            if(terminateFlag){
                aws.sendMessage("TERMINATE", URL_AM);
            }

            //STEP 8: End program and delete all resources.
            aws.deleteBucket(UNIQUE_KEY);
            aws.deleteMessages(URL_UK);
            aws.deleteSqs(URL_UK);
            System.out.println("The Results are Ready!");            
        }
    }

    //------------------------- helper functions ---------------------------//

    // public static void loadEnv() {
    //     String envFilePath = ".env";
    //     try (BufferedReader br = new BufferedReader(new FileReader(envFilePath))) {
    //         String line;
    //         while ((line = br.readLine()) != null) {
    //             // Ignore comments (lines starting with #) and empty lines
    //             if (line.trim().isEmpty() || line.trim().startsWith("#")) {
    //                 continue;
    //             }

    //             // Split the line into key-value pair
    //             String[] keyValue = line.split("=", 2);
    //             if (keyValue.length == 2) {
    //                 String key = keyValue[0].trim();
    //                 String value = keyValue[1].trim();

    //                 // Set the environment variable in the Java process
    //                 System.setProperty(key, value);
    //             }
    //         }
    //     } catch (IOException e) {
    //         System.err.println("Error reading .env file: " + e.getMessage());
    //     }
    // }

    public static void convertTextToHtml(String inputFilePath) {
        String newPath = inputFilePath.substring(0,inputFilePath.length() - 4) +".html";
        try{
            BufferedReader reader = new BufferedReader(new FileReader(inputFilePath));
            BufferedWriter writer = new BufferedWriter(new FileWriter(newPath)); 

            // Read the content from the text file.
            StringBuilder textContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                textContent.append(line).append("<br>");
            }

            // Wrap the text content with HTML tags.
            String htmlContent = "<!DOCTYPE html>\n" +
                    "<html lang=\"en\">\n" +
                    "<head>\n" +
                    "    <meta charset=\"UTF-8\">\n" +
                    "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                    "    <title>Text to HTML Conversion</title>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "    <pre>\n" +
                    textContent.toString() +
                    "    </pre>\n" +
                    "</body>\n" +
                    "</html>";

                    reader.close();
            // Write the HTML content to the output file.
            writer.write(htmlContent);
            writer.close();

            System.out.println("Conversion successful. HTML file saved to: " + newPath);

        } catch (IOException e) {
            e.printStackTrace();
        }
    } 
}

