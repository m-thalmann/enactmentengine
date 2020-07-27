package at.enactmentengine.serverless.main;

import at.enactmentengine.serverless.nodes.FunctionNode;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.Socket;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class to handle service requests.
 *
 * @author stefanpedratscher
 */
public class Handler implements Runnable {

    /**
     * Logger for request handler.
     */
    private final static Logger LOGGER = Logger.getLogger(Handler.class.getName());

    /**
     * Connected client sending the request.
     */
    private Socket socket;

    /**
     * Default constructor for handler.
     *
     * @param socket client connecting to service.
     */
    public Handler(Socket socket) {
        this.socket = socket;
    }

    /**
     * Handle request.
     */
    @Override
    public void run() {

        /* Get input and output stream */
        InputStream in = null;
        OutputStream out = null;
        try {
            in = socket.getInputStream();
            out = new FileOutputStream(Thread.currentThread().getId() + ".yaml");
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        /* Read bytes and write to output stream */
        byte[] bytes = new byte[16 * 1024];
        int count;
        try {
            assert in != null;
            while ((count = in.read(bytes)) >= 0) {
                assert out != null;
                out.write(bytes, 0, count);
                if (in.available() == 0) {
                    break;
                }
            }
            assert out != null;
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

        /* Start measuring time for workflow execution */
        Long start = System.currentTimeMillis();

        /* Get the execution id of the workflow execution */
        FunctionNode.executionId = getExecutionId();

        /* Execute the workflow */
        Executor executor = new Executor();
        Map<String, Object> executionResult = executor.executeWorkflow(Thread.currentThread().getId() + ".yaml");

        /* Stop measuring time for workflow execution */
        Long end = System.currentTimeMillis();

        /* Prepare the execution result */
        Map<String, Object> result = new HashMap<>();

        /* Workflow result */
        result.put("wfResult", executionResult);

        /* Workflow execution identifier */
        result.put("executionId", FunctionNode.executionId);

        /* Enactment engine statistics */
        Map<String, Object> eeStats = new HashMap<>();
        eeStats.put("start", new Timestamp(start + TimeZone.getTimeZone("Europe/Rome").getOffset(start)).toString());
        eeStats.put("end", new Timestamp(end + TimeZone.getTimeZone("Europe/Rome").getOffset(start)).toString());
        result.put("eeStats", eeStats);

        /* Send back json string because other modules might not have GSON */
        String jsonResult = new Gson().toJson(result);
        LOGGER.log(Level.INFO, "Sending back result " + jsonResult);

        /* Send response back to client */
        DataOutputStream dOut;
        try {
            dOut = new DataOutputStream(socket.getOutputStream());
            dOut.writeUTF(jsonResult);
            dOut.flush();
        } catch (IOException e1) {
            e1.printStackTrace();
        }

        /* Close connection */
        try {
            in.close();
            assert out != null;
            out.close();
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Get the execution identifier from the logger service
     *
     * @return execution identifier
     */
    private int getExecutionId(){
        try {
            /* Connect to logger service */
            LOGGER.info("Connecting to logger service...");
            Socket loggerService = new Socket("logger-service", 9005);

            /* Prepare and send request */
            JsonObject request = new JsonObject();
            request.addProperty("requestType", "GET_EXECUTION_ID");
            String requestString = request.toString();
            LOGGER.info("Sending request " + requestString + "...");
            ObjectOutputStream out = new ObjectOutputStream(loggerService.getOutputStream());
            out.writeObject(requestString);
            out.flush();

            /* Wait for response (wait for filtered resources) */
            LOGGER.info("Waiting for response from logger service...");
            ObjectInputStream objectInputStream = new ObjectInputStream(loggerService.getInputStream());
            String result = (String) objectInputStream.readObject();

            /* Close connection */
            LOGGER.info("Closing connection to logger service...");
            loggerService.close();

            /* Check if logger service returned a valid execution identifier */
            if(result == null || (result != null && "-1".equals(result))){
                LOGGER.warning("Logger service returned an invalid executionId.");
                return -1;
            }

            /* Return response */
            LOGGER.info("Response got: " + result);
            return Integer.parseInt(result);
        } catch (IOException | ClassNotFoundException e) {

            /* Log error on failure */
            LOGGER.severe("Could not get execution Id: " + e.getLocalizedMessage());
            return -1;
        }
    }
}
