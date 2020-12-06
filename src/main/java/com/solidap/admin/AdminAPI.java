package com.solidap.admin;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.gson.*;
import com.stripe.Stripe;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;


public class AdminAPI implements HttpFunction {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(AdminAPI.class.getName());
    private static final Firestore FIRESTORE = FirestoreOptions.getDefaultInstance().getService();
    private FirebaseApp defaultApp;
    private FirebaseAuth FIREBASE_AUTH;

    @Override
    public void service(HttpRequest request, HttpResponse response)
            throws IOException {

        Properties prop = new Properties();

        try (InputStream input = new FileInputStream("src/main/resources/application.properties")) {
            prop.load(input);
        } catch (IOException ex) {
            logger.info("ERROR in file(): " + ex.getMessage());

        }

        FirebaseOptions options = FirebaseOptions.builder().
                setCredentials(GoogleCredentials.getApplicationDefault()).setProjectId(prop.getProperty("projectID")).build();
        if (FirebaseApp.getApps().isEmpty()) {
            defaultApp = FirebaseApp.initializeApp(options);
        }
        FIREBASE_AUTH = FirebaseAuth.getInstance(defaultApp);

        logger.info("!!!" + request.getPath());

        if (request.getPath().equals(prop.getProperty("clientPath") + "/admin/getCustomers")) {
            getCustomers(request,response);
        }

        if (request.getPath().equals(prop.getProperty("clientPath") + "/admin/getCarts")) {
            getCarts(request,response);
        }

        if (request.getPath().equals(prop.getProperty("clientPath") + "/admin/deleteCustomers")) {
            deleteCustomers(request,response);
        }

        switch(request.getPath()) {
            case "/getCustomer":
                getCustomerDetails(request.getFirstQueryParameter("uid").get() , request,response);
                break;
            case "/getOrders":
                getOrders(request,response);
                break;
            case "/getCustomerOrders":
                getCustomerOrders(request.getFirstQueryParameter("uid").get(),request,response);
                break;
            case "/getOrder":
                getOrder(request.getFirstQueryParameter("uid").get() , request.getFirstQueryParameter("orderId").get(),request,response);
                break;
            default:
                // code block
        }
    }

    public void getCustomers(HttpRequest request, HttpResponse response) throws IOException {
        JsonArray responseObject = new JsonArray();
        for (CollectionReference customerCollection : FIRESTORE.
                collection("customers").
                document("customers").listCollections()) {
            JsonObject customerElm = new JsonObject();
            //End Customer info
            int orderCount = 0;
            float orderSum = 0;
            for (DocumentReference order : customerCollection.document("orders").collection("orders").listDocuments()) {
                try {
                    if (order.get().get().getData().get("status").equals("paid")) {
                        orderCount++;
                        orderSum+= Float.parseFloat(((Map<String, Object>) order.get().get().getData().get("cart")).get("grandTotal").toString());
                    }
                } catch (InterruptedException e) {
                    logger.log(Level.SEVERE,e.getMessage());
                } catch (ExecutionException e) {
                    logger.log(Level.SEVERE,e.getMessage());
                }
            }

            if (orderCount > 0) {
                customerElm.addProperty("orders" , orderCount);
                customerElm.addProperty("ordersTotal" , orderSum);
                customerElm.addProperty("id" , customerCollection.getId());
                //Get customer Personal Info
                try {
                    Map <String ,Object > personalInfo = customerCollection.document("personalInfo")
                            .get().get().getData();
                    if (personalInfo != null) {
                        customerElm.add("personalInfo" ,
                                JsonParser.parseString(gson.toJson(customerCollection.document("personalInfo")
                                        .get().get().getData())).getAsJsonObject());
                    }

                } catch (InterruptedException e) {
                    logger.log(Level.SEVERE,e.getMessage());
                } catch (ExecutionException e) {
                    logger.log(Level.SEVERE,e.getMessage());
                }
                responseObject.add(customerElm);
            }

        }

        setCORS(request,response);

        JsonObject data = new JsonObject();
        data.add("data",responseObject);

        BufferedWriter writer = response.getWriter();
        writer.write(data.toString());
    }

    public void getCarts(HttpRequest request, HttpResponse response) throws IOException {
        JsonArray responseObject = new JsonArray();
        for (CollectionReference customerCollection : FIRESTORE.
                collection("customers").
                document("customers").listCollections()) {
            JsonObject customerElm = new JsonObject();
            //End Customer info
            try {
                if (customerCollection.document("orders").get().get().get("orderCount") == null) {
                    customerElm.addProperty("orders" , 0);
                    customerElm.addProperty("ordersTotal" , 0);
                    customerElm.addProperty("id" , customerCollection.getId());
                }

            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
            responseObject.add(customerElm);
        }

        setCORS(request,response);

        JsonObject data = new JsonObject();
        data.add("data",responseObject);

        BufferedWriter writer = response.getWriter();
        writer.write(data.toString());
    }

    public void getCustomerDetails(String uid , HttpRequest request, HttpResponse response) throws IOException {
        JsonObject responseObject = new JsonObject();

        try {
            //FIREBASE_AUTH.createUser()
            logger.info("!!!!!!!!!!!!!!!" + FIREBASE_AUTH.getUser(uid).getEmail());

        } catch (FirebaseAuthException e) {
            logger.info("ERROR" + e.getMessage());

            e.printStackTrace();
        }

        CollectionReference customerCollection = FIRESTORE.collection("customers").
                document("customers").
                collection(uid);
        //Get Personal Info
        try {
            Map <String ,Object > personalInfo = customerCollection.document("personalInfo")
                    .get().get().getData();
            if (personalInfo != null) {
                responseObject.add("personalInfo" ,
                        JsonParser.parseString(gson.toJson(customerCollection.document("personalInfo")
                                .get().get().getData())).getAsJsonObject());
                responseObject.addProperty("created" ,customerCollection.document("personalInfo").get().get().getCreateTime().toSqlTimestamp().getTime());
            }


            Map <String ,Object > address = customerCollection.document("address")
                    .get().get().getData();

            if (address != null) {
                responseObject.add("address" ,
                        JsonParser.parseString(gson.toJson(customerCollection.document("address")
                                .get().get().getData())).getAsJsonObject());
            }
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE,e.getMessage());
        } catch (ExecutionException e) {
            logger.log(Level.SEVERE,e.getMessage());
        }

        //Get Orders
        TreeMap<Long, Object> dateSortedOrders = new TreeMap<Long, Object>(Collections.reverseOrder());
        int orderCount = 0;
        float orderSum = 0;

        for (DocumentReference order : customerCollection.document("orders").collection("orders").listDocuments()) {
            try {
                if (order.get().get().getData().get("status").equals("paid")) {
                    JsonObject orderObject = new JsonObject();
                    orderObject.addProperty("orderId" , order.getId());
                    orderCount++;
                    orderSum+= Float.parseFloat(((Map<String, Object>) order.get().get().getData().get("cart")).get("grandTotal").toString());
                    orderObject.addProperty("grandTotal" , ((Map<String, Object>) order.get().get().getData().get("cart")).get("grandTotal").toString());
                    orderObject.addProperty("date" , ((Map<String, Object>) order.get().get().getData().get("cart")).get("date").toString());

                    dateSortedOrders.put((Long)((Map<String, Object>) order.get().get().getData().get("cart")).get("date"),orderObject);
                }
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE,e.getMessage());
            } catch (ExecutionException e) {
                logger.log(Level.SEVERE,e.getMessage());
            }
        }
        responseObject.add("orders" , JsonParser.parseString(gson.toJson(dateSortedOrders.values())));
        responseObject.addProperty("ordersCount" , orderCount);
        responseObject.addProperty("ordersTotal" , orderSum);
        setCORS(request,response);

        BufferedWriter writer = response.getWriter();
        writer.write(responseObject.toString());
    }

    public void getOrders(HttpRequest request, HttpResponse response) throws IOException {
        JsonArray responseObject = new JsonArray();
        TreeMap<Long, Object> dateSortedOrders = new TreeMap<Long, Object>(Collections.reverseOrder());
        for (CollectionReference customerCollection : FIRESTORE.
                collection("customers").
                document("customers").listCollections()) {
            for (DocumentReference order : customerCollection.document("orders").collection("orders").listDocuments()) {
                try {
                    if (order.get().get().getData().get("status").equals("paid")) {
                        JsonObject orderObject = new JsonObject();
                        orderObject.addProperty("orderId",order.getId());
                        orderObject.addProperty("uid",customerCollection.getId());
                        orderObject.addProperty("date",((Map<String, Object>) order.get().get().getData().get("cart")).get("date").toString());
                        orderObject.addProperty("grandTotal",((Map<String, Object>) order.get().get().getData().get("cart")).get("grandTotal").toString());
                        orderObject.addProperty("itemCount",((ArrayList<Object>)((Map<String, Object>) order.get().get().getData().get("cart")).get("cart")).size());
                        orderObject.add("personalInfo",JsonParser.parseString(gson.toJson(order.get().get().getData().get("personalInfo"))));

                        dateSortedOrders.put((Long) ((Map<String, Object>) order.get().get().getData().get("cart")).get("date"),
                                orderObject);
                    }
                } catch (InterruptedException e) {
                    logger.log(Level.SEVERE,e.getMessage());
                } catch (ExecutionException e) {
                    logger.log(Level.SEVERE,e.getMessage());
                }
            }
        }
        setCORS(request,response);

        BufferedWriter writer = response.getWriter();
        writer.write(JsonParser.parseString(gson.toJson(dateSortedOrders.values())).toString());
    }

    public void getCustomerOrders(String uid,HttpRequest request, HttpResponse response) throws IOException {
        JsonArray responseObject = new JsonArray();
        TreeMap<Long, Object> dateSortedOrders = new TreeMap<Long, Object>(Collections.reverseOrder());
        CollectionReference customerCollection = FIRESTORE.
                collection("customers").
                document("customers").collection(uid);
        for (DocumentReference order : customerCollection.document("orders").collection("orders").listDocuments()) {
            try {
                if (order.get().get().getData().get("status").equals("paid")) {
                    JsonObject orderObject = new JsonObject();
                    orderObject.addProperty("orderId",order.getId());
                    orderObject.addProperty("uid",customerCollection.getId());
                    orderObject.addProperty("date",((Map<String, Object>) order.get().get().getData().get("cart")).get("date").toString());
                    orderObject.addProperty("grandTotal",((Map<String, Object>) order.get().get().getData().get("cart")).get("grandTotal").toString());
                    orderObject.addProperty("itemCount",((ArrayList<Object>)((Map<String, Object>) order.get().get().getData().get("cart")).get("cart")).size());
                    orderObject.add("personalInfo",JsonParser.parseString(gson.toJson(order.get().get().getData().get("personalInfo"))));

                    dateSortedOrders.put((Long) ((Map<String, Object>) order.get().get().getData().get("cart")).get("date"),
                            orderObject);
                }
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE,e.getMessage());
            } catch (ExecutionException e) {
                logger.log(Level.SEVERE,e.getMessage());
            }
        }
        setCORS(request,response);

        BufferedWriter writer = response.getWriter();
        writer.write(JsonParser.parseString(gson.toJson(dateSortedOrders.values())).toString());
    }

    public void getOrder(String uid , String orderId, HttpRequest request, HttpResponse response) throws IOException {

        JsonObject responseObject = new JsonObject();
        try {

            DocumentReference order = FIRESTORE.
                    collection("customers").
                    document("customers").collection(uid)
                    .document("orders").collection("orders").document(orderId);
            responseObject.add("order",JsonParser.parseString(gson.toJson(order.get().get().getData())));
                /*responseObject.add("payment",
                        JsonParser.parseString(gson.toJson(order.collection("payment").limit(1).get().get().getDocuments().get(0).getData().get("payment"))));
                responseObject.addProperty("orderId",order.getId());*/


        } catch (InterruptedException e) {
            logger.log(Level.SEVERE,e.getMessage());
        } catch (ExecutionException e) {
            logger.log(Level.SEVERE,e.getMessage());
        }


        setCORS(request,response);

        logger.log(Level.INFO,responseObject.toString());
        BufferedWriter writer = response.getWriter();
        writer.write(responseObject.toString());
    }

    public void deleteCustomers(HttpRequest request, HttpResponse response) throws IOException{
        JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            if (!setCORS(request,response)) {
            for (JsonElement customer : requestParsed.get("data").getAsJsonArray() ) {
                boolean canDelete = true;
                boolean exists = true;
                for (DocumentReference order : FIRESTORE.collection("customers").
                        document("customers").collection(customer.getAsJsonObject().get("id").getAsString())
                        .document("orders").collection("orders").listDocuments()) {
                    try {
                        if (order.get().get().getData().get("status").equals("paid")) {
                            canDelete = false;
                            break;
                        }
                    } catch (InterruptedException e) {
                        logger.log(Level.SEVERE,e.getMessage());
                    } catch (ExecutionException e) {
                        logger.log(Level.SEVERE,e.getMessage());
                    }
                }
                if (canDelete) {
                    try {
                        try{
                            FIREBASE_AUTH.getUser(customer.getAsJsonObject().get("id").getAsString());
                        } catch(FirebaseAuthException e) {
                            exists = false;
                        }
                        if(exists) {
                            FIREBASE_AUTH.deleteUser(customer.getAsJsonObject().get("id").getAsString());
                        }
                        for (DocumentReference document : FIRESTORE.collection("customers").
                                document("customers")
                                .collection(customer.getAsJsonObject().get("id").getAsString()).listDocuments()) {
                            if ("orders".equals(document.getId())) {
                                for (DocumentReference order : document.collection("orders").listDocuments()) {
                                    order.delete();
                                }
                            } else if ("testOrders".equals(document.getId())) {
                                for (DocumentReference testOrder : document.collection("orders").listDocuments()) {
                                    for (DocumentReference payment : testOrder.collection("payment").listDocuments()) {
                                        logger.info("!!!!!!!!!!!!!!!" + payment.getId());
                                        payment.delete();
                                    }
                                    testOrder.delete();
                                }
                            } else if ("favorites".equals(document.getId())) {
                                for (DocumentReference favItem : document.collection("favorites").listDocuments()) {
                                    favItem.delete();
                                }
                            } else {
                                document.delete();
                            }
                        }
                    } catch (FirebaseAuthException e) {
                        logger.info("ERROR" + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }

                JsonObject data = new JsonObject();
                JsonObject success = new JsonObject();
                success.addProperty("status","success");
                data.add("data",success);
                BufferedWriter writer = response.getWriter();
                writer.write(data.toString());
            }
    }

    public boolean setCORS(HttpRequest request,HttpResponse response) throws IOException{
        // Set CORS headers
        //   Allows GETs from any origin with the Content-Type
        //   header and caches preflight response for 3600s
        response.appendHeader("Access-Control-Allow-Origin", "*");
        response.appendHeader("Access-Control-Allow-Headers", "*");
        response.setContentType("application/json");

        if ("OPTIONS".equals(request.getMethod())) {
            response.appendHeader("Access-Control-Allow-Methods", "GET");
            response.appendHeader("Access-Control-Allow-Headers", "Content-Type");
            response.appendHeader("Access-Control-Max-Age", "3600");
            response.setStatusCode(HttpURLConnection.HTTP_NO_CONTENT);

            BufferedWriter writer = response.getWriter();
            writer.write("CORS headers set successfully!");

            return true;
        }
        return false;
    }
}