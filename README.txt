DSP course

Assignment 1: Sarcasm Analysis

Authors:
Ben Bandarkar 318468758
Shira Edri 211722764

Instance type:
ami- 00e95a9222311e8ed //  standard amazon linux ami
type- M4_LARGE

Implementation:
usage
>java -jar LocalApp.jar <inputFileName1> ... <inputFileNameN> <outputFileName1> ... <outputFileNameN> <n> [terminate]

where:
        for x \in {1, ... , N}:
            inputFileNameX: input file X's name.
                ** the files are text files containing lists of reviews. 
            outputFileNameX: output file X's name.
        n: is number of jobs per worker for the task.
        terminate: put 'terminate' string to terminate the program (optional).


Explanation:

LOCAL APP:

Local App starts a manager instance if one is not found.
Local App creates a unique bucket and uploads the input files to this bucket.
The Local App waiting for the Manager to open SQS queue "APP_TO_MANAGER".
Then, it sends a message to the Manager including all the necessary information about the task: "UNIQUE_KEY|||n|||FirstFileIndex|||LastFileIndex".
if terminate flag in on, then the Local App sends a termination message to the Manager.
The Local App waiting for a response from the Manager idicating the process is done and then downloads the output files from s3. 
finally, the Local App delete the unique SQS queue and the unique bucket.

MANAGER:

Once the manager starts, he creates 3 queue: 
    1. APP_TO_MANAGER 
    2. MANAGER_TO_WORKER
    3. WORKER_TO_MANAGER

The manager's work is preformed using 2 main threads, with 2 different tasks that runs in parallel:
    1. apps listener
    2. workers listener

The first one listens on incoming messages from different local applications throgh APP_TO_MANAGER queue. 
Once it receives a new message it:

it extracts the file location and downloads it. 
submits the message as a task to a threadPool. 
each thread in the threadPool breaking the job to files and submit the job to another threadPool.
each thread in the other threadPool breaking the job of each file to n reviews per single job.
Then send it to the workers throgh MANAGER_TO_WORKER queue and creates calculated amount of workers according to the amount of tasks.
If a terminate message is received, the terminateFlag will turn on in order to announce termination.

The second thread listens on the completed job from workers through WORKER_TO_MANAGER queue.
Once it receives a new message it:

submits the message as a task to a threadPool.
each thread in the threadPool sums up the jobs to match files and check whether the whole task is complete.
If the task is done, the thread is uploads the file to the match location, and sends to the local application a message indicating the process is done. 



WORKER:

The workers waiting for a job from the manager through MANAGER_TO_WORKER queue.
Once a worker receives a message: "BucketName+fileIndex$$n|||link_1 $$$ text_1 $$$ rating_1||| ... |||link_n $$$ text_n $$$ rating_n", it extracts all the information.
Then, the worker apply sentiment analysis on the reviews it got and detect whether it is sarcasm or not.
Then he send the parsed result to the manager through WORKER_TO_MANAGER queue.
Finally, remove the processed message from the MANAGER_TO_WORKER queue.



Runtime:
1. 1 app with terminate, 5 file(1577 KB), n = 10 : 15 min
2. 1 app, 5 files(1577 KB), n = 30 : 8 min
3. 2 apps, 1 with terminate , 5 files for each(1557 KB), n = 10 : 20 min
4. 2 apps 5 files for each, n_1 = 10, n_2 = 20 (with terminate) : 17 min
5. 3 apps, 1 with terminate, 5 files for each, n = 10 : 25 min




Security:

We securely stored the credentials in a designated folder on our local computer, ensuring that they remain inaccessible to unauthorized parties. 
By keeping the sensitive information offline, within our premises, we maintain strict control over who can access and retrieve these details.
Our system is designed to ensure that this critical data remains isolated and protected, as it is not included or accessible within the codebase.


Scalability:

We've designed the project for scalability, meaning it dynamically adjusts the number of worker computers based on workload. 
We assume the users select a reasonable n according to workload, and the system opens an appropriate number of workers to handle tasks efficiently without overloading. 
However, due to AWS limitations for students, we're constrained to running a maximum of 9 computers simultaneously.
While theoretically, multiple managers could handle a large client base, our project using a single manager according to the requirements. 
To enhance manager efficiency within its limits, we employ a ThreadPool where each task runs concurrently in separate threads. 
This strategy ensures flexibility without burdening the manager with intensive tasks.


Persistence:

If a worker node dies it's job will eventually timeout.The next time the manager polls the incoming task queue, it will re-evaluate the necessary-workers amount and create new worker instances.
When a termination message arrives in the incoming tasks queue, the manager updates it state. When all former tasks are done it terminates the workers, purges the queues and closes itself.
A maximum-workers amount was hard-coded to ensure the system doesn't exceed the maximum instances limit.
The manager doesn't do any of the parsing work. Each worker node's work is completely agnostic to the any other worker node's work.

When a worker node fails, messages are not deleted immediately. 
Deletion occurs only after a worker completes its task. 
We set a visibility timeout of 5n minutes to avoid interrupting ongoing work due to large inputs. 
If a worker fails to complete within this time, the message becomes visible again for another worker to handle.


Threads:

As part of our scalability strategy, we integrated a custom ThreadPool-based implementation into the manager code. 
This optimizes the manager's performance in handling message exchanges with a large number of users.
Threads can be problematic if they complicate the code unnecessarily.


Termination:
As soon as the manager receives a terminate message, he closes the APPTOMANAGER queue,
Then he waits for the two main threads (a thread listening to LOCALAPPS and a thread listening to WORKERS) to finish working.
Both threads are programmed to check if a TERMINATE message has been received, and if Yes, they first finish all their tasks and only then finish. 
After the two threads are finished, the manager eliminates all the WORKERS, closes the two remaining queues (MANAGERTOWORKER and WORKERTOMANAGER) 
and at the end of the process the manager turns itself off.

