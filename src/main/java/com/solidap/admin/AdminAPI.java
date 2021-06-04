package com.solidap.admin;

import com.google.api.client.extensions.appengine.http.UrlFetchTransport;
import com.google.api.client.googleapis.apache.GoogleApacheHttpTransport;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.*;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.ExportedUserRecord;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.gson.*;
import com.stripe.Stripe;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
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
    private static final JacksonFactory jacksonFactory = new JacksonFactory();

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

        if (adminAuth(request,prop)) {
            switch(request.getPath()) {
                case "/getCustomers":
                    getCustomers(request, response);
                    break;
                case "/getCustomer":
                    getCustomerDetails(request, response);
                    break;
                case "/getCarts":
                    getCarts(request, response);
                    break;
                case "/deleteCarts":
                    deleteCarts(request, response);
                    break;
                case "/cleanupUsers":
                    cleanupUsers(request, response);
                    break;
                case "/getOrders":
                    getOrders(request, response);
                    break;
                case "/getNewOrders":
                    getNewOrders(request, response);
                    break;
                case "/getCustomerOrders":
                    getCustomerOrders(request, response);
                    break;
                case "/getOrder":
                    getOrder(request, response);
                    break;
                case "/fulfillOrder":
                    fullFillOrder(request, response);
                    break;
                case "/unfulfillOrder":
                    unFullFillOrder(request, response);
                    break;
                case "/reviewCart":
                    reviewCart(request, response);
                    break;
                case "/unReviewCart":
                    unReviewCart(request, response);
                    break;
                case "/statsForPeriod":
                    statsForPeriod(prop,request, response);
                    break;
                default:
                    // code block
            }
        } else {
            //Unauthorized
            response.setStatusCode(401);
        }
    }

    public boolean adminAuth(HttpRequest request,Properties prop) {
        boolean verified = false;
        if ("OPTIONS".equals(request.getMethod())) {
            verified = true;
        } else {
            if (request.getHeaders().get("Authorization")!=null && !request.getHeaders().get("Authorization").isEmpty()) {
                try {
                        String token = request.getHeaders().get("Authorization").get(0).substring(7);
                        FirebaseToken decodedToken = FIREBASE_AUTH.verifyIdToken(token);
                        String email = decodedToken.getEmail();

                        List<String>  adminEmails = Arrays.asList(prop.getProperty("adminEmail").split(","));

                        if(adminEmails.contains(email)) {
                            verified = true;
                        }

                    } catch (FirebaseAuthException e) {
                        logger.log(Level.SEVERE, e.getMessage());
                    }
            }
        }
        return verified;
    }

    public void getCustomers(HttpRequest request, HttpResponse response) throws IOException {
        if (!setCORS(request, response)) {
            JsonArray responseObject = new JsonArray();
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            for (CollectionReference customerCollection : FIRESTORE.
                    collection("customers").
                    document("customers").listCollections()) {
                JsonObject customerElm = new JsonObject();
                //End Customer info
                int orderCount = 0;
                float orderSum = 0;
                try {
                    if (customerCollection.document(getEnv(requestParsed)).get().get().get("orderCount") != null) {

                        customerElm.addProperty("orders", customerCollection.document(getEnv(requestParsed)).get().get().get("orderCount").toString());
                        customerElm.addProperty("ordersTotal", customerCollection.document(getEnv(requestParsed)).get().get().get("ordersTotal").toString());
                        customerElm.addProperty("id", customerCollection.getId());
                        //Get customer Personal Info
                        try {
                            Map<String, Object> personalInfo = customerCollection.document("personalInfo")
                                    .get().get().getData();
                            if (personalInfo != null) {
                                customerElm.add("personalInfo",
                                        JsonParser.parseString(gson.toJson(customerCollection.document("personalInfo")
                                                .get().get().getData())).getAsJsonObject());
                                customerElm.addProperty("isRegistered" , true);
                            } else {
                                for (QueryDocumentSnapshot doc: customerCollection.document(getEnv(requestParsed)).collection("orders").limit(1).get().get().getDocuments()) {
                                    customerElm.add("personalInfo",
                                            JsonParser.parseString(gson.toJson(doc.getData().get("personalInfo"))).getAsJsonObject());
                                    customerElm.addProperty("isRegistered" , false);
                                }
                            }

                        } catch (InterruptedException e) {
                            logger.log(Level.SEVERE, e.getMessage());
                        } catch (ExecutionException e) {
                            logger.log(Level.SEVERE, e.getMessage());
                        }
                        responseObject.add(customerElm);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
            JsonObject data = new JsonObject();
            data.add("data", responseObject);

            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    }

    public void getCarts(HttpRequest request, HttpResponse response) throws IOException {
        if (!setCORS(request, response)) {
            JsonArray responseObject = new JsonArray();
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            TreeMap<Long, Object> dateSortedCarts = new TreeMap<Long, Object>(Collections.reverseOrder());
            for (CollectionReference customerCollection : FIRESTORE.
                    collection("customers").
                    document("customers").listCollections()) {
                try {
                    if (customerCollection.document(getEnv(requestParsed)).get().get().get("orderCount") == null) {
                        for (DocumentReference order : customerCollection.document(getEnv(requestParsed)).collection("orders").listDocuments()) {
                            try {
                                if (((ArrayList<Object>) ((Map<String, Object>) order.get().get().getData().get("cart")).get("cart"))!=null) {
                                    JsonObject cartElm = new JsonObject();

                                    if (order.collection("admin").get().get().isEmpty()) {
                                        cartElm.addProperty("status", "new");
                                    } else {
                                        cartElm.addProperty("status", "complete");
                                    }
                                    cartElm.addProperty("cartId", order.getId());
                                    cartElm.addProperty("uid", customerCollection.getId());
                                    cartElm.add("personalInfo", JsonParser.parseString(gson.toJson(order.get().get().getData().get("personalInfo"))));
                                    cartElm.addProperty("date", String.valueOf(order.get().get().getCreateTime().toDate().getTime()));
                                    cartElm.addProperty("itemCount", ((ArrayList<Object>) ((Map<String, Object>) order.get().get().getData().get("cart")).get("cart")).size());

                                    dateSortedCarts.put((Long) order.get().get().getCreateTime().toDate().getTime(),
                                            cartElm);
                                }

                            } catch (InterruptedException e) {
                                logger.log(Level.SEVERE, e.getMessage());
                            } catch (ExecutionException e) {
                                logger.log(Level.SEVERE, e.getMessage());
                            }

                        }
                    }

                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }

            JsonObject data = new JsonObject();
            data.add("data", JsonParser.parseString(gson.toJson(dateSortedCarts.values())));

            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    }

    public void getCustomerDetails(HttpRequest request, HttpResponse response) throws IOException {

        if (!setCORS(request, response)) {
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            JsonObject responseObject = new JsonObject();

            CollectionReference customerCollection = FIRESTORE.collection("customers").
                    document("customers").
                    collection(requestParsed.get("data").getAsJsonObject().get("uid").getAsString());
            //Get Personal Info
            try {
                Map<String, Object> personalInfo = customerCollection.document("personalInfo")
                        .get().get().getData();
                if (personalInfo != null) {
                    responseObject.add("personalInfo",
                            JsonParser.parseString(gson.toJson(customerCollection.document("personalInfo")
                                    .get().get().getData())).getAsJsonObject());
                    responseObject.addProperty("created", customerCollection.document("personalInfo").get().get().getCreateTime().toSqlTimestamp().getTime());
                } else {
                    for (QueryDocumentSnapshot doc: customerCollection.document(getEnv(requestParsed)).collection("orders").limit(1).get().get().getDocuments()) {
                        responseObject.add("personalInfo",
                                JsonParser.parseString(gson.toJson(doc.getData().get("personalInfo"))).getAsJsonObject());
                    }
                }


                Map<String, Object> address = customerCollection.document("address")
                        .get().get().getData();

                if (address != null) {
                    responseObject.add("address",
                            JsonParser.parseString(gson.toJson(customerCollection.document("address")
                                    .get().get().getData())).getAsJsonObject());
                }
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage());
            } catch (ExecutionException e) {
                logger.log(Level.SEVERE, e.getMessage());
            }

            //Get Orders
            TreeMap<Long, Object> dateSortedOrders = new TreeMap<Long, Object>(Collections.reverseOrder());
            int orderCount = 0;
            float orderSum = 0;

            for (DocumentReference order : customerCollection.document(getEnv(requestParsed)).collection("orders").listDocuments()) {
                try {
                    if (order.get().get().getData().get("status").equals("paid")) {
                        JsonObject orderObject = new JsonObject();
                        orderObject.addProperty("orderId", order.getId());
                        orderCount++;
                        orderSum += Float.parseFloat(((Map<String, Object>) order.get().get().getData().get("cart")).get("grandTotal").toString());
                        orderObject.addProperty("grandTotal", ((Map<String, Object>) order.get().get().getData().get("cart")).get("grandTotal").toString());
                        orderObject.addProperty("date", ((Map<String, Object>) order.get().get().getData().get("cart")).get("date").toString());

                        dateSortedOrders.put((Long) ((Map<String, Object>) order.get().get().getData().get("cart")).get("date"), orderObject);
                    }
                } catch (InterruptedException e) {
                    logger.log(Level.SEVERE, e.getMessage());
                } catch (ExecutionException e) {
                    logger.log(Level.SEVERE, e.getMessage());
                }
            }
            responseObject.add("orders", JsonParser.parseString(gson.toJson(dateSortedOrders.values())));
            responseObject.addProperty("ordersCount", orderCount);
            responseObject.addProperty("ordersTotal", orderSum);

            JsonObject data = new JsonObject();
            data.add("data", responseObject);

            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());

        }
    }

    public void getOrders(HttpRequest request, HttpResponse response) throws IOException {


        if (!setCORS(request, response)) {
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            JsonArray responseObject = new JsonArray();
            TreeMap<Long, Object> dateSortedOrders = new TreeMap<Long, Object>(Collections.reverseOrder());

            for (CollectionReference customerCollection : FIRESTORE.
                    collection("customers").
                    document("customers").listCollections()) {
                JsonObject customerElm = new JsonObject();
                try {
                    if (customerCollection.document(getEnv(requestParsed)).get().get().get("orderCount") != null) {
                        for (DocumentReference order : customerCollection.document(getEnv(requestParsed)).collection("orders").listDocuments()) {
                            try {
                                if (order.get().get().getData().get("status").equals("paid")) {
                                    JsonObject orderObject = new JsonObject();
                                    if (order.collection("admin").get().get().isEmpty()) {
                                        orderObject.addProperty("status", "new");
                                    } else {
                                        orderObject.addProperty("status", "complete");
                                    }
                                    orderObject.addProperty("orderId", order.getId());
                                    orderObject.addProperty("uid", customerCollection.getId());
                                    orderObject.addProperty("date", ((Map<String, Object>) order.get().get().getData().get("cart")).get("date").toString());
                                    orderObject.addProperty("grandTotal", ((Map<String, Object>) order.get().get().getData().get("cart")).get("grandTotal").toString());
                                    orderObject.addProperty("itemCount", ((ArrayList<Object>) ((Map<String, Object>) order.get().get().getData().get("cart")).get("cart")).size());
                                    orderObject.add("personalInfo", JsonParser.parseString(gson.toJson(order.get().get().getData().get("personalInfo"))));

                                    dateSortedOrders.put((Long) ((Map<String, Object>) order.get().get().getData().get("cart")).get("date"),
                                            orderObject);
                                }
                            } catch (InterruptedException e) {
                                logger.log(Level.SEVERE, e.getMessage());
                            } catch (ExecutionException e) {
                                logger.log(Level.SEVERE, e.getMessage());
                            }
                        }

                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
            JsonObject data = new JsonObject();
            data.add("data", JsonParser.parseString(gson.toJson(dateSortedOrders.values())));

            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    }

    public void getNewOrders(HttpRequest request, HttpResponse response) throws IOException {
        if (!setCORS(request, response)) {
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            JsonArray responseObject = new JsonArray();
            TreeMap<Long, Object> dateSortedOrders = new TreeMap<Long, Object>(Collections.reverseOrder());

            for (CollectionReference customerCollection : FIRESTORE.
                    collection("customers").
                    document("customers").listCollections()) {
                JsonObject customerElm = new JsonObject();
                try {
                    if (customerCollection.document(getEnv(requestParsed)).get().get().get("orderCount") != null) {
                        for (DocumentReference order : customerCollection.document(getEnv(requestParsed)).collection("orders").listDocuments()) {
                            try {
                                if (order.collection("admin").get().get().isEmpty() && order.get().get().getData().get("status").equals("paid")) {
                                    JsonObject orderObject = new JsonObject();
                                    orderObject.addProperty("orderId", order.getId());
                                    orderObject.addProperty("status", "new");
                                    orderObject.addProperty("uid", customerCollection.getId());
                                    orderObject.addProperty("date", ((Map<String, Object>) order.get().get().getData().get("cart")).get("date").toString());
                                    orderObject.addProperty("grandTotal", ((Map<String, Object>) order.get().get().getData().get("cart")).get("grandTotal").toString());
                                    orderObject.addProperty("itemCount", ((ArrayList<Object>) ((Map<String, Object>) order.get().get().getData().get("cart")).get("cart")).size());
                                    orderObject.add("personalInfo", JsonParser.parseString(gson.toJson(order.get().get().getData().get("personalInfo"))));

                                    dateSortedOrders.put((Long) ((Map<String, Object>) order.get().get().getData().get("cart")).get("date"),
                                            orderObject);
                                }
                            } catch (InterruptedException e) {
                                logger.log(Level.SEVERE, e.getMessage());
                            } catch (ExecutionException e) {
                                logger.log(Level.SEVERE, e.getMessage());
                            }
                        }

                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
            JsonObject data = new JsonObject();
            data.add("data", JsonParser.parseString(gson.toJson(dateSortedOrders.values())));

            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    }

    public void getCustomerOrders(HttpRequest request, HttpResponse response) throws IOException {

        if (!setCORS(request, response)) {
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            JsonObject responseObject = new JsonObject();
            TreeMap<Long, Object> dateSortedOrders = new TreeMap<Long, Object>(Collections.reverseOrder());
            CollectionReference customerCollection = FIRESTORE.
                    collection("customers").
                    document("customers").collection(requestParsed.get("data").getAsString());
            for (DocumentReference order : customerCollection.document("orders").collection("orders").listDocuments()) {
                try {
                    if (order.get().get().getData().get("status").equals("paid")) {
                        JsonObject orderObject = new JsonObject();
                        orderObject.addProperty("orderId", order.getId());
                        orderObject.addProperty("uid", customerCollection.getId());
                        orderObject.addProperty("date", ((Map<String, Object>) order.get().get().getData().get("cart")).get("date").toString());
                        orderObject.addProperty("grandTotal", ((Map<String, Object>) order.get().get().getData().get("cart")).get("grandTotal").toString());
                        orderObject.addProperty("itemCount", ((ArrayList<Object>) ((Map<String, Object>) order.get().get().getData().get("cart")).get("cart")).size());
                        orderObject.add("personalInfo", JsonParser.parseString(gson.toJson(order.get().get().getData().get("personalInfo"))));

                        dateSortedOrders.put((Long) ((Map<String, Object>) order.get().get().getData().get("cart")).get("date"),
                                orderObject);
                    }
                } catch (InterruptedException e) {
                    logger.log(Level.SEVERE, e.getMessage());
                } catch (ExecutionException e) {
                    logger.log(Level.SEVERE, e.getMessage());
                }
            }
            JsonObject data = new JsonObject();
            data.add("data", JsonParser.parseString(gson.toJson(dateSortedOrders.values())));

            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    }

    public void getOrder(HttpRequest request, HttpResponse response) throws IOException {

        if (!setCORS(request, response)) {
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            JsonObject responseObject = new JsonObject();

            try {

                DocumentReference order = FIRESTORE.
                        collection("customers").
                        document("customers").collection(requestParsed.get("data").getAsJsonObject().get("uid").getAsString())
                        .document(getEnv(requestParsed)).collection("orders").document(requestParsed.get("data").getAsJsonObject().get("orderId").getAsString());
                responseObject.add("order", JsonParser.parseString(gson.toJson(order.get().get().getData())));
                if(!order.collection("admin").get().get().isEmpty()) {
                    for(QueryDocumentSnapshot adminDoc : order.collection("admin").get().get().getDocuments()) {
                        responseObject.add(adminDoc.getId(),JsonParser.parseString(gson.toJson(adminDoc.getData())));
                    }
                }

            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage());
            } catch (ExecutionException e) {
                logger.log(Level.SEVERE, e.getMessage());
            }
            JsonObject data = new JsonObject();
            data.add("data", responseObject);

            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    }

    public void fullFillOrder(HttpRequest request, HttpResponse response) throws IOException {

        if (!setCORS(request, response)) {
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            JsonObject responseObject = new JsonObject();

            Map<String, Object> fullfillData = new HashMap<String, Object>();
            fullfillData.put("status", true);
            fullfillData.put("updateDate", System.currentTimeMillis());
            DocumentReference order = FIRESTORE.
                    collection("customers").
                    document("customers").collection(requestParsed.get("data").getAsJsonObject().get("uid").getAsString())
                    .document(getEnv(requestParsed)).collection("orders").document(requestParsed.get("data").getAsJsonObject().get("orderId").getAsString());
            order.collection("admin").document("fullfilment").create(fullfillData);


            responseObject.addProperty("status", 200);

            JsonObject data = new JsonObject();
            data.add("data", responseObject);

            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    }

    public void unFullFillOrder(HttpRequest request, HttpResponse response) throws IOException {

        if (!setCORS(request, response)) {
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            JsonObject responseObject = new JsonObject();

            DocumentReference order = FIRESTORE.
                    collection("customers").
                    document("customers").collection(requestParsed.get("data").getAsJsonObject().get("uid").getAsString())
                    .document(getEnv(requestParsed)).collection("orders").document(requestParsed.get("data").getAsJsonObject().get("orderId").getAsString());
            order.collection("admin").document("fullfilment").delete();


            responseObject.addProperty("status", 200);

            JsonObject data = new JsonObject();
            data.add("data", responseObject);

            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    }

    public void reviewCart(HttpRequest request, HttpResponse response) throws IOException {

        if (!setCORS(request, response)) {
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            JsonObject responseObject = new JsonObject();

            Map<String, Object> reviewData = new HashMap<String, Object>();
            reviewData.put("status", true);
            reviewData.put("updateDate", System.currentTimeMillis());
            DocumentReference order = FIRESTORE.
                    collection("customers").
                    document("customers").collection(requestParsed.get("data").getAsJsonObject().get("uid").getAsString())
                    .document(getEnv(requestParsed)).collection("orders").document(requestParsed.get("data").getAsJsonObject().get("orderId").getAsString());
            order.collection("admin").document("review").create(reviewData);


            responseObject.addProperty("status", 200);

            JsonObject data = new JsonObject();
            data.add("data", responseObject);

            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    }

    public void unReviewCart(HttpRequest request, HttpResponse response) throws IOException {

        if (!setCORS(request, response)) {
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            JsonObject responseObject = new JsonObject();

            DocumentReference order = FIRESTORE.
                    collection("customers").
                    document("customers").collection(requestParsed.get("data").getAsJsonObject().get("uid").getAsString())
                    .document(getEnv(requestParsed)).collection("orders").document(requestParsed.get("data").getAsJsonObject().get("orderId").getAsString());
            order.collection("admin").document("review").delete();


            responseObject.addProperty("status", 200);

            JsonObject data = new JsonObject();
            data.add("data", responseObject);

            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    }

    public void deleteCarts(HttpRequest request, HttpResponse response) throws IOException {

        if (!setCORS(request, response)) {
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            for (JsonElement cart : requestParsed.get("data").getAsJsonObject().get("carts").getAsJsonArray()) {
                DocumentReference cartDoc = FIRESTORE.collection("customers").
                        document("customers").collection(cart.getAsJsonObject().get("uid").getAsString())
                        .document(getEnv(requestParsed)).collection("orders").document(cart.getAsJsonObject().get("id").getAsString());
                try {
                    if (cartDoc.get().get().getData().get("status").equals("paid")) {
                        break;
                    } else {
                        if (cartDoc.collection("admin") != null ) {
                            for (DocumentReference document : cartDoc.collection("admin").listDocuments()){
                                document.delete();
                            }
                        }
                        try {
                            cartDoc.delete();
                        } finally {
                            if (FIRESTORE.collection("customers").
                                    document("customers").collection(cart.getAsJsonObject().get("uid").getAsString()).get().get().isEmpty()) {
                                FIREBASE_AUTH.deleteUser(cart.getAsJsonObject().get("uid").getAsString());
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    logger.log(Level.SEVERE, e.getMessage());
                } catch (ExecutionException e) {
                    logger.log(Level.SEVERE, e.getMessage());
                } catch (FirebaseAuthException e) {
                    logger.log(Level.SEVERE, e.getMessage());
                }
            }

            JsonObject data = new JsonObject();
            JsonObject success = new JsonObject();
            success.addProperty("status", "success");
            data.add("data", success);
            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    }

    public void cleanupUsers(HttpRequest request, HttpResponse response) throws IOException{
        if (!setCORS(request, response)) {
            try {
                for (Iterator<ExportedUserRecord> i = FIREBASE_AUTH.listUsers(null).getValues().iterator(); i.hasNext(); ) {
                    ExportedUserRecord user = i.next();
                    FIREBASE_AUTH.deleteUser(user.getUid());
                }

            } catch (FirebaseAuthException e) {
                logger.log(Level.SEVERE, e.getMessage());
            }
            JsonObject data = new JsonObject();
            JsonObject success = new JsonObject();
            success.addProperty("status", "success");
            data.add("data", success);
            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    }
/**
    public void deleteUsers(HttpRequest request, HttpResponse response) throws IOException {


        if (!setCORS(request, response)) {
            JsonArray responseObject = new JsonArray();
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            for (CollectionReference customerCollection : FIRESTORE.
                    collection("customers").
                    document("customers").listCollections()) {
                JsonObject customerElm = new JsonObject();
                //End Customer info
                int orderCount = 0;
                float orderSum = 0;
                try {
                    if (customerCollection.document(getEnv(requestParsed)).get().get().get("orderCount") != null) {

                        customerElm.addProperty("orders", customerCollection.document(getEnv(requestParsed)).get().get().get("orderCount").toString());
                        customerElm.addProperty("ordersTotal", customerCollection.document(getEnv(requestParsed)).get().get().get("ordersTotal").toString());
                        customerElm.addProperty("id", customerCollection.getId());
                        //Get customer Personal Info
                        try {
                            Map<String, Object> personalInfo = customerCollection.document("personalInfo")
                                    .get().get().getData();
                            if (personalInfo != null) {
                                customerElm.add("personalInfo",
                                        JsonParser.parseString(gson.toJson(customerCollection.document("personalInfo")
                                                .get().get().getData())).getAsJsonObject());
                            }

                        } catch (InterruptedException e) {
                            logger.log(Level.SEVERE, e.getMessage());
                        } catch (ExecutionException e) {
                            logger.log(Level.SEVERE, e.getMessage());
                        }
                        responseObject.add(customerElm);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
            JsonObject data = new JsonObject();
            data.add("data", responseObject);

            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }

        if (!setCORS(request, response)) {
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            for (JsonElement cart : requestParsed.get("data").getAsJsonArray()) {
                boolean canDelete = true;
                DocumentReference cartDoc = FIRESTORE.collection("customers").
                        document("customers").collection(cart.getAsJsonObject().get("uid").getAsString())
                        .document("orders").collection("orders").document(cart.getAsJsonObject().get("id").getAsString());
                try {
                    if (cartDoc.get().get().getData().get("status").equals("paid")) {
                        canDelete = false;
                        break;
                    }
                } catch (InterruptedException e) {
                    logger.log(Level.SEVERE, e.getMessage());
                } catch (ExecutionException e) {
                    logger.log(Level.SEVERE, e.getMessage());
                }
                if (canDelete) {
                    try {
                        try {
                            FIREBASE_AUTH.getUser(customer.getAsJsonObject().get("id").getAsString());
                        } catch (FirebaseAuthException e) {
                            exists = false;
                        }
                        if (exists) {
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
            success.addProperty("status", "success");
            data.add("data", success);
            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    } **/

    public void statsForPeriod(Properties prop,HttpRequest request, HttpResponse response) throws IOException {

        if (!setCORS(request, response)) {
            JsonObject requestParsed = gson.fromJson(request.getReader(), JsonObject.class);
            JsonObject responseObject = new JsonObject();

            double ordersTotal = 0;
            int ordersCount = 0;
            try {
                    if (!requestParsed.get("data").getAsJsonObject().get("quickLook").isJsonNull()) {
                        String quickLook = requestParsed.get("data").getAsJsonObject().get("quickLook").getAsString();
                        Instant now = Instant.now();

                        if (quickLook.equals("today")) {
                            ZonedDateTime zdt = ZonedDateTime.ofInstant(now, TimeZone.getTimeZone(prop.getProperty("timezone")).toZoneId());
                            Calendar currentDate = GregorianCalendar.from(zdt);
                            String day = String.valueOf(currentDate.get(Calendar.DAY_OF_MONTH));
                            String month = String.valueOf(currentDate.get(Calendar.MONTH));
                            String year = String.valueOf(currentDate.get(Calendar.YEAR));

                            for(CollectionReference order: FIRESTORE.collection(getStatsEnv(requestParsed))
                                    .document(year).
                                            collection(month).
                                            document(day).listCollections()) {
                                for(DocumentReference doc: order.listDocuments()){
                                    JsonElement orderData = JsonParser.parseString(gson.toJson(doc.get().get().getData()));
                                    ordersCount++;
                                   ordersTotal = ordersTotal + Double.valueOf(orderData.getAsJsonObject().get("cart").getAsJsonObject().get("grandTotal").getAsString());
                                }
                            }
                        } else if(quickLook.equals("yesterday")) {
                            Instant yesterday = now.minus(1, ChronoUnit.DAYS);
                            ZonedDateTime zdt = ZonedDateTime.ofInstant(yesterday, TimeZone.getTimeZone(prop.getProperty("timezone")).toZoneId());
                            Calendar currentDate = GregorianCalendar.from(zdt);
                            String day = String.valueOf(currentDate.get(Calendar.DAY_OF_MONTH));
                            String month = String.valueOf(currentDate.get(Calendar.MONTH));
                            String year = String.valueOf(currentDate.get(Calendar.YEAR));

                            for(CollectionReference order: FIRESTORE.collection(getStatsEnv(requestParsed))
                                    .document(year).
                                            collection(month).
                                            document(day).listCollections()) {
                                for(DocumentReference doc: order.listDocuments()){
                                    JsonElement orderData = JsonParser.parseString(gson.toJson(doc.get().get().getData()));
                                    ordersCount++;
                                    ordersTotal = ordersTotal + Double.valueOf(orderData.getAsJsonObject().get("cart").getAsJsonObject().get("grandTotal").getAsString());
                                }
                            }
                        } else if(quickLook.equals("week") || quickLook.equals("month") ) {
                            ZonedDateTime zonedCurrentDate = ZonedDateTime.ofInstant(now, TimeZone.getTimeZone(prop.getProperty("timezone")).toZoneId());
                            Calendar currentDate = GregorianCalendar.from(zonedCurrentDate);
                            int daysUntil;
                            if (quickLook.equals("week")) {
                                daysUntil = currentDate.get(Calendar.DAY_OF_WEEK);
                            } else {
                                daysUntil = currentDate.get(Calendar.DAY_OF_MONTH);
                            }
                            for (int dow=1; dow <= daysUntil; dow++) {
                                Instant thatDay = now.minus(dow-1 , ChronoUnit.DAYS);
                                ZonedDateTime zdt = ZonedDateTime.ofInstant(thatDay, TimeZone.getTimeZone(prop.getProperty("timezone")).toZoneId());
                                Calendar thatDayCalendar = GregorianCalendar.from(zdt);
                                String day = String.valueOf(thatDayCalendar.get(Calendar.DAY_OF_MONTH));
                                String month = String.valueOf(thatDayCalendar.get(Calendar.MONTH));
                                String year = String.valueOf(thatDayCalendar.get(Calendar.YEAR));

                                for(CollectionReference order: FIRESTORE.collection(getStatsEnv(requestParsed))
                                        .document(year).
                                                collection(month).
                                                document(day).listCollections()) {
                                    for(DocumentReference doc: order.listDocuments()){
                                        JsonElement orderData = JsonParser.parseString(gson.toJson(doc.get().get().getData()));
                                        ordersCount++;
                                        ordersTotal = ordersTotal + Double.valueOf(orderData.getAsJsonObject().get("cart").getAsJsonObject().get("grandTotal").getAsString());
                                    }
                                }
                            }
                        }
                    } else if (!requestParsed.get("data").getAsJsonObject().get("startDate").isJsonNull()) {
                        JsonObject startDateJSON = requestParsed.get("data").getAsJsonObject().get("startDate").getAsJsonObject();
                        String day = String.valueOf(startDateJSON.get("day").getAsInt());
                        String month = String.valueOf(startDateJSON.get("month").getAsInt());
                        String year = String.valueOf(startDateJSON.get("year").getAsInt());

                        for(CollectionReference order: FIRESTORE.collection(getStatsEnv(requestParsed))
                                .document(year).
                                        collection(month).
                                        document(day).listCollections()) {
                            for(DocumentReference doc: order.listDocuments()){
                                JsonElement orderData = JsonParser.parseString(gson.toJson(doc.get().get().getData()));
                                ordersCount++;
                                ordersTotal = ordersTotal + Double.valueOf(orderData.getAsJsonObject().get("cart").getAsJsonObject().get("grandTotal").getAsString());
                            }
                        }
                    }

            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage());
            } catch (ExecutionException e) {
                logger.log(Level.SEVERE, e.getMessage());
            }
            responseObject.addProperty("count", ordersCount);
            responseObject.addProperty("total", ordersTotal);
            JsonObject data = new JsonObject();
            data.add("data", responseObject);

            BufferedWriter writer = response.getWriter();
            writer.write(data.toString());
        }
    }

    public String getEnv(JsonObject requestParsed) {
        if(requestParsed.get("data").getAsJsonObject().get("env").getAsString().equals("prod")) {
            return "orders";
        } else {
            return "testOrders";
        }
    }

    public String getStatsEnv(JsonObject requestParsed) {
        if(requestParsed.get("data").getAsJsonObject().get("env").getAsString().equals("prod")) {
            return "stats";
        } else {
            return "testStats";
        }
    }

    public boolean setCORS(HttpRequest request, HttpResponse response) throws IOException {
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