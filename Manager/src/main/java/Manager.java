import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.model.Message;
import java.io.BufferedReader;
import java.io.IOException;

public class Manager{

//----------------------Fields----------------------//
    
    final static AWS aws = AWS.getInstance();
    
    //Url_AM is the url of the APP_TO_MANAGER queue.
    static String url_AM = "";

    //Url_MW is the url of the MANAGER_TO_WORKER queue.
    static String url_MW = "";

    //Url_WM is the url of the WORKER_TO_MANAGER queue.
    static String url_WM = "";

    static boolean terminateFlag = false;

    static final int MAX_WORKERS = 6;

    static Integer apps_count = 0;

    //Workers_Counter is a counter for all active workers.
    static Integer workers_Counter = 0;

    //App_WC is a map representing the amount of workers per application.
    static Map<String, Integer> app_WC = new HashMap<String, Integer>();

    //App_FN is a map representing applications' key and their files name.
    static Map<String, String[]> app_FN = new HashMap<String, String[]>();

    //files_Ans is a map representing files and their parsed outputs.
    static Map<String, String> files_Ans = new HashMap<>();

    //freeWorker_list is a list of free workers.
    static List<String> freeWorker_list = new ArrayList<String>();

    
//-------------------------MAIN function-------------------------//
    public static void main(String[] args){

        //STEP 1: creates queues.
        url_AM = aws.createSqsQueue("APP_TO_MANAGER");
        url_MW = aws.createSqsQueue("MANAGER_TO_WORKER");
        url_WM = aws.createSqsQueue("WORKER_TO_MANAGER");

        //STEP 2: creates and starts threads.
        Task1 apps_listener = new Task1();
        Task4 workers_listener = new Task4();
        
        Thread thread1 = new Thread(apps_listener);
        Thread thread4 = new Thread(workers_listener);
        
        thread1.start();
        thread4.start();

        // Wait for the thread1 and thread4 to complete.
        try {
            thread1.join();
            thread4.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
      
        // STEP 3: close resources.
        aws.terminateWorkers(freeWorker_list);
        aws.deleteSqs(url_MW);
        aws.deleteSqs(url_WM);
        aws.terminateManager();
    }

//-------------------------HELPER functions & Threads-------------------------//  

    //======================= APPS LISTENER =======================//

    //Task1 purpose is checking the APP_TO_MANAGER queue and submit the messages(Task2) to executorService.
    static class Task1 implements Runnable { 

        boolean exitFlag = false; 
        ExecutorService executorService = Executors.newCachedThreadPool();
        
        @Override
        public void run(){
            while (!exitFlag) {
                // receive messages from the APP_TO_MANAGER queue.
                List<Message> messages = aws.receiveMessages(url_AM);
                
                // check if got a terminatation message.
                for (Message m : messages) {
                    if(m.body().equals("TERMINATE")){  
                        terminateFlag = true;
                        exitFlag = true;
                    } 
                    
                    else {
                        synchronized(apps_count){apps_count++;} 
                        Runnable task2 = new Task2(m.body());
                        executorService.submit(task2);
                    }
                    aws.deleteMessages(url_AM, m.receiptHandle());
                }
                
                if(terminateFlag){
                    messages = aws.receiveMessages(url_AM);
                    aws.deleteSqs(url_AM);
                    
                    for (Message m : messages) {
                        synchronized(apps_count){apps_count++;} 
                        Runnable task2 = new Task2(m.body());
                        executorService.submit(task2);
                        aws.deleteMessages(url_AM, m.receiptHandle());
                    }
                    executorService.shutdown();

                    try {
                        while (!executorService.awaitTermination(60, TimeUnit.MILLISECONDS)) {
                            // Waiting for all threads to terminate
                            System.out.printf("[DEBUG] Manager - There are still tasks in progress\n");
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }    

    //Task2 purpose is defining the job for each file and submit the job to executorService.
    static class Task2 implements Runnable {
        
        String message;

        public Task2(String m){ 
            this.message = m;
        }

        @Override
        public void run(){
            //Step 1: extract relevant data from message. 
            String[] splitted = message.split("\\|\\|\\|");
            String bucket_key= splitted[0];
            int n = Integer.parseInt(splitted[1]);
            int Si = Integer.parseInt(splitted[2]);
            int Ei = Integer.parseInt(splitted[3]);

            //Step 2: writes messages for workers.
            ExecutorService executorService = Executors.newCachedThreadPool();
            String[] filesNames = new String[Ei - Si + 1];
            for(int i = Si; i <= Ei; i ++){
                filesNames[i - Si] = bucket_key + "file" + String.valueOf(i);
                Runnable task3Runnable = new Task3(bucket_key, String.valueOf(i), n);
                executorService.submit(task3Runnable);
            }
            synchronized(app_FN){app_FN.put(bucket_key, filesNames);}
            executorService.shutdown();
        }
    }

    //Task 3 porpuse is downloading files from s3, and define the jobs to workers according to n.
    static class Task3 implements Runnable {
        
        String appKey;
        String fileKey;
        int n;

        public Task3(String localKey, String fileKey, int n){
            this.appKey = localKey;
            this.fileKey = fileKey;
            this.n = n;
        }

        @Override
        public void run() {

            JsonReview[] allFileReviews;
            int counter_worker = 0;
            int i = 0, j = 0;

            try (BufferedReader br = aws.downloadFile(appKey, fileKey)) {
                String line;
                String msg1 = appKey + "file" + String.valueOf(fileKey) + "$$" + n + "|||" ;
                while ((line = br.readLine()) != null) {
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonData jsonData = objectMapper.readValue(line, JsonData.class);
                    allFileReviews = jsonData.getReviews();
                    while(i < n && j < allFileReviews.length){
                        msg1 = msg1 + allFileReviews[i].getLink() + " $$$ ";
                        msg1 = msg1 + allFileReviews[i].getText() + " $$$ ";
                        msg1 = msg1 + String.valueOf(allFileReviews[j].getRating()) + "|||";
                        i++; j++;
                    }

                    if(i == n){
                        i = 0;
                        counter_worker++;
                        String msg = msg1.substring(0, msg1.length() - 3);
                        aws.sendMessage(msg, url_MW);
                        msg1 =  appKey + "file" + String.valueOf(fileKey) + "$$" + n + "|||" ;
                    }
                    else{j = 0;}
                }

                if(!msg1.equals(appKey + "file" + String.valueOf(fileKey) + "$$" + n + "|||")){
                    counter_worker++;
                    String msg = msg1.substring(0, msg1.length() - 3);
                    aws.sendMessage(msg, url_MW);
                }

            } catch (IOException e) {
                e.printStackTrace();
            }

            int required_worker = counter_worker;
            // Add requiredWorkers to the localKey if it exists, otherwise put the key-value pair in the map
            synchronized(app_WC){ // appdating the Amount of workers needed for LocalApp
                app_WC.compute(appKey, (appKey, currentNW) -> (currentNW == null) ? required_worker : currentNW + required_worker);

            }
            System.out.printf("[DEBUG] Manager - Enter workers_handler - %d\n", required_worker);
            workers_handler(required_worker);
        }  
    }

    //Workers_handler get a number of required number of worker, and activate workers accordingly.
    public static void workers_handler(int required_workers){
    
        synchronized(workers_Counter){
            System.out.printf("[DEBUG] Manager - synchronized workers_Counter\n");
            workers_Counter = (Integer)aws.workersAmount();

            //CASE 1: The Manager has maximum workers.
            synchronized(freeWorker_list){
                System.out.printf("[DEBUG] Manager - synchronized freeWorker_list\n");

                if(required_workers <= freeWorker_list.size()){
                    System.out.printf("[DEBUG] Manager - Workers_Handler - case 1\n");

                    int diff = freeWorker_list.size() - required_workers;
                    for(int i = 0; i < diff; i++){
                        String instanceId = freeWorker_list.remove(0);
                        aws.terminateInstance(instanceId);
                    }
                    freeWorker_list.clear();
                }
                //CASE 2: The Manager need to open more Workers.
                else{
                    System.out.printf("[DEBUG] Manager - Workers_Handler - case 2\n");
                    int diff = required_workers - freeWorker_list.size();
                    if(workers_Counter + diff <= MAX_WORKERS){
                        System.out.printf("[DEBUG] Manager - Workers_Handler - case 2.1 - %d\n", diff);
                        aws.createWorkers("worker", diff);
                        workers_Counter += diff;
                    }
                    else{ //(workers_counter + diff > 8)

                        System.out.printf("[DEBUG] Manager - Workers_Handler - case 2.2 - %d\n", (MAX_WORKERS - workers_Counter));
                        aws.createWorkers("worker", (MAX_WORKERS - workers_Counter));
                        workers_Counter = MAX_WORKERS;
                    }
                    freeWorker_list.clear();
                }
            }
        }
    }


    //======================= WORKERS LISTENER =======================//

     //Task4 purpose is checking the WORKER_TO_MANAGER queue and submit it to executorService.
    static class Task4 implements Runnable { 

        boolean exitFlag = false; 

        @Override
        public void run(){
            ExecutorService executorService = Executors.newCachedThreadPool();
            while (!exitFlag) {
                // receive messages from the queue
                List<Message> messages = aws.receiveMessages(url_WM);
                // delete messages from the queue
                for (Message m : messages) {
                    Runnable task5 = new Task5(m.body());
                    executorService.submit(task5);
                    aws.deleteMessages(url_WM, m.receiptHandle());
                }
                synchronized(apps_count){
                    if (apps_count.intValue() == 0 && terminateFlag && app_FN.isEmpty()) {
                        executorService.shutdown();
                        exitFlag = true;
                        try {
                            while (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                                // Waiting for all threads to terminate
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            System.out.printf("[DEBUG] Manager - Thread number 4 - finished\n");
        }
    }

    

    //Task5 purpose is writing and sending messages to applications through MANAGER_TO_APP sqs.
    static class Task5 implements Runnable { 

        String message;
        Task5(String message){
            this.message = message;
        }

        @Override
        public void run(){
            //Step 1: extract relevent data from the message.
            String[] splitted = message.split("\\|\\|\\|");
            String instanceId = splitted[0];
            String appId_fileName = splitted[1];
            String res = splitted[2];
            String appId = appId_fileName.split("file")[0];
            
            synchronized(files_Ans){
                // Add current answer to the key if it exists, otherwise put the key-value pair in the map
                if(files_Ans.containsKey(appId_fileName)){
                    String str = files_Ans.get(appId_fileName);
                    files_Ans.replace(appId_fileName, str + res);
                } 
                else {
                    files_Ans.put(appId_fileName, res);
                }
            }

            synchronized(app_WC){
                int currentAmount = app_WC.get(appId) - 1;
                app_WC.put(appId, currentAmount);
                System.out.printf("[DEBUG] Manager - CURRENTAMOUNT - %d \n", currentAmount);
                
                if(currentAmount == 0){
                    System.out.printf("[DEBUG] Manager - Thread number 5 - current amoount == 0\n");
                    app_WC.remove(appId);
                    System.out.printf("[DEBUG] Manager - localId removed from app_WC\n");
                    msgToLocalApp(appId);
                    System.out.printf("[DEBUG] Manager - msg sent\n");
                }
            }

            synchronized(freeWorker_list){freeWorker_list.add(instanceId);}
        }
    }
    
    public static void msgToLocalApp(String localKey){
            String[] files_name = app_FN.get(localKey);

            for(String file: files_name){
                aws.uploadFileToS3(localKey, file, files_Ans.get(file));
            }

            synchronized(app_FN){
                app_FN.remove(localKey);
            }

            String localUrl = aws.createSqsQueue(localKey);
            aws.sendMessage("DONE", localUrl);
            synchronized(apps_count){apps_count--;}
    }
}



//======================= HELPER CLASSES =======================//

// --------------------- JsonData Class --------------------- //
class JsonData {
    private String title;
    private JsonReview[] reviews;

    // Getters and setters
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public JsonReview[] getReviews() { return reviews; }
    public void setReviews(JsonReview[] reviews) { this.reviews = reviews; }
}

// --------------------- JsonReview Class --------------------- //
class JsonReview {
    private String title;
    private String id;
    private String link;
    private String text;
    private int rating;
    private String author;
    private String date;

    // Getters and setters
    @JsonProperty("title")
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    @JsonProperty("id")
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    @JsonProperty("link")
    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }
    @JsonProperty("text")
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    @JsonProperty("rating")
    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = rating; }
    @JsonProperty("author")
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    @JsonProperty("date")
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
}