package com.solidap.admin;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;


public class AdminAPI implements HttpFunction {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(AdminAPI.class.getName());
    private static final Firestore FIRESTORE = FirestoreOptions.getDefaultInstance().getService();

    @Override
    public void service(HttpRequest request, HttpResponse response)
            throws IOException {

        System.out.println(request.getMethod());
        switch(request.getPath()) {
            case "/getCustomers":
                getCustomers(request,response);
                break;
            case "/getCustomer":
                getCustomerDetails(request.getFirstQueryParameter("uid").get() , request,response);
                break;
            case "/getOrders":
                getOrders(request,response);
                break;
            case "/getOrder":
                getOrder(request.getQueryParameters(),request,response);
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
            customerElm.addProperty("id" , customerCollection.getId());
            //Get customer Personal Info
            try {
                customerElm.add("personalInfo" ,
                        JsonParser.parseString(gson.toJson(customerCollection.document("personalInfo")
                                .get().get().getData())).getAsJsonObject());

            } catch (InterruptedException e) {
                logger.log(Level.SEVERE,e.getMessage());
            } catch (ExecutionException e) {
                logger.log(Level.SEVERE,e.getMessage());
            }
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
            customerElm.addProperty("orders" , orderCount);
            customerElm.addProperty("ordersTotal" , orderSum);

            responseObject.add(customerElm);
        }

        setCORS(request,response);

        BufferedWriter writer = response.getWriter();
        writer.write(responseObject.toString());
    }

    public void getCustomerDetails(String uid , HttpRequest request, HttpResponse response) throws IOException {
        JsonObject responseObject = new JsonObject();
        CollectionReference customerCollection = FIRESTORE.collection("customers").
                document("customers").
                collection(uid);
        //Get Personal Info
        try {
            responseObject.add("personalInfo" ,
                    JsonParser.parseString(gson.toJson(customerCollection.document("personalInfo")
                    .get().get().getData())).getAsJsonObject());
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

        /*for (DocumentReference guestOrder : FIRESTORE.
                collection("orders").listDocuments()) {
            try {
                if (guestOrder.get().get().getData().get("status").equals("paid")) {
                    JsonObject orderObject = new JsonObject();
                    orderObject.addProperty("orderId",guestOrder.getId());
                    orderObject.addProperty("date",((Map<String, Object>) guestOrder.get().get().getData().get("cart")).get("date").toString());
                    orderObject.addProperty("grandTotal",((Map<String, Object>) guestOrder.get().get().getData().get("cart")).get("grandTotal").toString());
                    orderObject.addProperty("itemCount",((ArrayList<Object>)((Map<String, Object>) guestOrder.get().get().getData().get("cart")).get("cart")).size());
                    orderObject.add("personalInfo",JsonParser.parseString(gson.toJson(guestOrder.get().get().getData().get("personalInfo"))));

                    dateSortedOrders.put((Long) ((Map<String, Object>) guestOrder.get().get().getData().get("cart")).get("date"),
                            orderObject);
                }
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE,e.getMessage());
            } catch (ExecutionException e) {
                logger.log(Level.SEVERE,e.getMessage());
            }
        }*/

        setCORS(request,response);

        BufferedWriter writer = response.getWriter();
        writer.write(JsonParser.parseString(gson.toJson(dateSortedOrders.values())).toString());
    }

    public void getOrder(Map<String, List<String>> params, HttpRequest request, HttpResponse response) throws IOException {

        JsonObject responseObject = new JsonObject();
        try {
            if(params.get("uid").toString().length()>0) {
                DocumentReference order = FIRESTORE.
                        collection("customers").
                        document("customers").collection(params.get("uid").get(0).toString())
                        .document("orders").collection("orders").document(params.get("orderId").get(0).toString());
                responseObject.add("order",JsonParser.parseString(gson.toJson(order.get().get().getData())));
                responseObject.add("payment",
                        JsonParser.parseString(gson.toJson(order.collection("payment").limit(1).get().get().getDocuments().get(0).getData().get("payment"))));
                responseObject.addProperty("orderId",order.getId());

            } else {

            }
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

    public void setCORS(HttpRequest request,HttpResponse response) {
        // Set CORS headers
        //   Allows GETs from any origin with the Content-Type
        //   header and caches preflight response for 3600s
        response.appendHeader("Access-Control-Allow-Origin", "*");

        if ("OPTIONS".equals(request.getMethod())) {
            response.appendHeader("Access-Control-Allow-Methods", "GET");
            response.appendHeader("Access-Control-Allow-Headers", "Content-Type");
            response.appendHeader("Access-Control-Max-Age", "3600");
            response.setStatusCode(HttpURLConnection.HTTP_NO_CONTENT);
            return;
        }
    }
}