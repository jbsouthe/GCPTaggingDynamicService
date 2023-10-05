package com.singularity.ee.service.tagging.task;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.singularity.ee.agent.appagent.kernel.ServiceComponent;
import com.singularity.ee.agent.appagent.kernel.spi.IDynamicService;
import com.singularity.ee.agent.appagent.kernel.spi.IServiceContext;
import com.singularity.ee.agent.util.log4j.ADLoggerFactory;
import com.singularity.ee.agent.util.log4j.IADLogger;
import com.singularity.ee.service.tagging.AgentNodeProperties;
import com.singularity.ee.service.tagging.MetaData;
import com.singularity.ee.service.tagging.exception.CommunicationErrorException;
import com.singularity.ee.service.tagging.exception.ConfigurationException;
import com.singularity.ee.service.tagging.exception.NotRunningOnException;
import com.singularity.ee.service.tagging.model.AccessToken;
import com.singularity.ee.service.tagging.model.BatchTaggingRequest;
import com.singularity.ee.service.tagging.model.GCEInstance;
import com.singularity.ee.util.javaspecific.threads.IAgentRunnable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

public class GCPTaggingTask implements IAgentRunnable {
    private static final IADLogger logger = ADLoggerFactory.getLogger((String)"com.singularity.dynamicservice.tagging.GCPTaggingTask");
    private static String GCP_SERVICE_ACCOUNT_KEY_FILE_PROPERTY = "gcp-service-account-key-file";
    private static String CONTROLLER_URL_PROPERTY = "controller-url";
    private static String CONTROLLER_APICLIENT_PROPERTY = "controller-api-client";
    private static String CONTROLLER_APISECRET_PROPERTY = "controller-api-secret";
    private IDynamicService agentService;
    private AgentNodeProperties agentNodeProperties;
    private ServiceComponent serviceComponent;
    private IServiceContext serviceContext;
    private Gson gson;
    private String projectId;
    private String instanceName;
    private String zone;
    private GoogleCredentials credentials;
    private long lastSyncTimestamp = 0;
    private Properties properties;

    public GCPTaggingTask (IDynamicService agentService, AgentNodeProperties agentNodeProperties, ServiceComponent serviceComponent, IServiceContext iServiceContext) throws ConfigurationException, NotRunningOnException {
        this.agentNodeProperties=agentNodeProperties;
        this.agentService=agentService;
        this.serviceComponent=serviceComponent;
        this.serviceContext=iServiceContext;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.properties = initLocalProperties(serviceContext, CONTROLLER_URL_PROPERTY, CONTROLLER_APICLIENT_PROPERTY, CONTROLLER_APISECRET_PROPERTY, GCP_SERVICE_ACCOUNT_KEY_FILE_PROPERTY);
        // Fetch metadata
        try {
            projectId = fetchMetadata("http://metadata.google.internal/computeMetadata/v1/project/project-id");
            instanceName = fetchMetadata("http://metadata.google.internal/computeMetadata/v1/instance/name");
            String zonePath = fetchMetadata("http://metadata.google.internal/computeMetadata/v1/instance/zone");
            zone = zonePath.substring(zonePath.lastIndexOf('/') + 1);
        } catch (IOException e) {
            throw new NotRunningOnException();
        }
        String keyFileName = properties.getProperty(GCP_SERVICE_ACCOUNT_KEY_FILE_PROPERTY);
        try {
            credentials = ServiceAccountCredentials.fromStream(new FileInputStream(keyFileName))
                    .createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));
        } catch (Exception e) {
            throw new ConfigurationException("Error initializing credentials from file: "+ keyFileName +" Exception: "+ e.getMessage());
        }
    }

    private Properties initLocalProperties (IServiceContext serviceContext, String...verifyProperties) throws ConfigurationException {
        File configFile = new File(serviceContext.getRuntimeConfDir(), "tagging.properties");
        if( !configFile.exists() )
            configFile = new File(serviceContext.getBaseConfDir(), "tagging.properties");
        if( !configFile.exists() ) throw new ConfigurationException("Config file does not exist: "+ configFile.getAbsolutePath());
        if( !configFile.canRead() ) throw new ConfigurationException("Config file is not readable: "+ configFile.getAbsolutePath());
        Properties properties = new Properties();
        InputStream inputStream = null;

        try {
            // Load the properties file from the file system
            inputStream = new FileInputStream(configFile);

            // Load all the properties from this file
            properties.load(inputStream);

            StringBuilder errorStringList = new StringBuilder();
            for( String property : verifyProperties ) {
                if( properties.getProperty(property, "NOTSET").equals("NOTSET") )
                    errorStringList.append(String.format(" Missing required property: '%s'", property));
            }

            if( errorStringList.length() > 0 ) {
                throw new ConfigurationException("Error in reading configuration properties: "+ configFile.getAbsolutePath() + " Issues:"+ errorStringList.toString() );
            }

        } catch (IOException e) {
            throw new ConfigurationException("Error reading config file: "+ configFile.getAbsolutePath() +" Exception: "+ e.getMessage());
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    throw new ConfigurationException("IOException closing the property file: "+ e.getMessage());
                }
            }
        }
        return properties;
    }

    /**
     * When an object implementing interface <code>Runnable</code> is used
     * to create a thread, starting the thread causes the object's
     * <code>run</code> method to be called in that separately executing
     * thread.
     * <p>
     * The general contract of the method <code>run</code> is that it may
     * take any action whatsoever.
     *
     * @see Thread#run()
     */
    @Override
    public void run() {
        if(!agentNodeProperties.isEnabled()) {
            logger.info("Service " + agentService.getName() + " is not enabled. To enable it enable the node property "+ AgentNodeProperties.ENABLED_PROPERTY);
            return;
        }
        if( System.currentTimeMillis() < this.lastSyncTimestamp + (agentNodeProperties.getSyncFrequencyMinutes()*60000) )
            return; //only run this every 15 minutes, ish

        GCEInstance gceInstance = null;
        try {
            gceInstance = fetchInstanceData();
        } catch (IOException e) {
            logger.error("Error fetching GCP Instance Data");
        }

        if( gceInstance == null ) return; //give up

        BatchTaggingRequest batchTaggingRequest = new BatchTaggingRequest(gceInstance, serviceComponent.getConfigManager().getIConfigChannel());
        try {
            uploadTagsToController(batchTaggingRequest);
        } catch (CommunicationErrorException e) {
            logger.error("Communication Error in uploading tags: "+ e.getMessage());
        }

    }

    private void sendInfoEvent(String message) {
        sendInfoEvent(message, MetaData.getAsMap());
    }

    private void sendInfoEvent(String message, Map map) {
        logger.info("Sending Custom INFO Event with message: "+ message);
        if( !map.containsKey("plugin-version") ) map.putAll(MetaData.getAsMap());
        serviceComponent.getEventHandler().publishInfoEvent(message, map);
    }

    private String fetchMetadata(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("Metadata-Flavor", "Google");

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            result.append(line);
        }
        reader.close();
        return result.toString();
    }

    private GCEInstance fetchInstanceData() throws IOException {
        String urlString = String.format("https://compute.googleapis.com/compute/v1/projects/%s/zones/%s/instances/%s", projectId, zone, instanceName);
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("Authorization", "Bearer " + credentials.refreshAccessToken().getTokenValue());

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        return gson.fromJson(reader, GCEInstance.class);
    }

    private String getControllerBearerToken() throws CommunicationErrorException {
        try {
            // Prepare the POST parameters
            StringBuilder postData = new StringBuilder();
            postData.append(URLEncoder.encode("grant_type", StandardCharsets.UTF_8.toString()));
            postData.append('=');
            postData.append(URLEncoder.encode("client_credentials", StandardCharsets.UTF_8.toString()));
            postData.append('&');
            postData.append(URLEncoder.encode("client_id", StandardCharsets.UTF_8.toString()));
            postData.append('=');
            postData.append(URLEncoder.encode(properties.getProperty(CONTROLLER_APICLIENT_PROPERTY), StandardCharsets.UTF_8.toString()));
            postData.append('&');
            postData.append(URLEncoder.encode("client_secret", StandardCharsets.UTF_8.toString()));
            postData.append('=');
            postData.append(URLEncoder.encode(properties.getProperty(CONTROLLER_APISECRET_PROPERTY), StandardCharsets.UTF_8.toString()));

            byte[] postDataBytes = postData.toString().getBytes(StandardCharsets.UTF_8);

            // Initialize and configure HttpURLConnection
            URL url = new URL(properties.getProperty(CONTROLLER_URL_PROPERTY) + "/controller/api/oauth/access_token");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
            connection.setDoOutput(true);

            // Write the POST parameters to the output stream
            try (OutputStream os = connection.getOutputStream()) {
                os.write(postDataBytes);
                os.flush();
            }

            // Handle the response
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuilder response = new StringBuilder();

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                AccessToken accessToken = gson.fromJson(response.toString(), AccessToken.class);
                connection.disconnect();
                return accessToken.access_token;
            } else {
                throw new CommunicationErrorException("Controller Response in Error for token request: "+ connection.getResponseMessage());
            }


        } catch (CommunicationErrorException communicationErrorException) {
            throw communicationErrorException;
        } catch (Exception e) {
            throw new CommunicationErrorException("Error in token generation, Exception: "+ e.getMessage());
        }
    }

    private void uploadTagsToController(BatchTaggingRequest batchTaggingRequest) throws CommunicationErrorException {
        try {
            // Convert the JSON object to a String
            String jsonString = gson.toJson(batchTaggingRequest);

            // Initialize and configure the HttpURLConnection
            URL url = new URL(properties.getProperty(CONTROLLER_URL_PROPERTY) + "/controller/restui/tags/tagEntitiesInBatch");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + getControllerBearerToken());
            connection.setDoOutput(true);

            // Write the JSON body to the output stream
            try (OutputStream os = connection.getOutputStream()) {
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
                writer.write(jsonString);
                writer.flush();
            }

            // Handle the response
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new CommunicationErrorException("Error updating tags for entity, Response: "+ connection.getResponseMessage());
            }

            connection.disconnect();
        } catch (Exception e) {
            throw new CommunicationErrorException(e.getMessage());
        }
    }
}
