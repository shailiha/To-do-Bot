/**
 *
 * @author Jessica Nickson
 * @date 19/06/2011
 *
 */
package ircbot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.MutableDateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormat;
import java.sql.*;
import org.joda.time.DateTimeZone;

public class ToDoBot {

    final static String botName = "pigeon";
    final static String todoFormatError = "Parsing failed. todo formats accepted: ~todo @dd/MM/yyyy hh:mm [message] "
            + "OR ~todo @dd/MM/yyyy [message] OR ~todo @hh:mm [message]";

    //Create connection to server and while there is input to read, read it!
    public static void main(String args[]) throws UnknownHostException, IOException, ClassNotFoundException, SQLException {
        //Try to connect to the database
        Connection dbConn = null;
        try {
            dbConn = createDatabaseConnection();
            DatabaseMetaData meta = dbConn.getMetaData();
            ResultSet results = meta.getTables(null, null, "ToDoList", null);
            Statement stat = dbConn.createStatement();
            if (!results.next()) {
                System.out.println("Creating table");
                stat.execute("CREATE TABLE ToDoList (date TEXT NOT NULL, time TEXT NOT NULL, user TEXT NOT NULL, todo TEXT NOT NULL);");
                stat.executeUpdate("INSERT INTO ToDoList VALUES ('0', '0', '0', '0');");
            } else {
                System.out.println("Table exists");
            }
        } catch (SQLException ex) {
            Logger.getLogger(ToDoBot.class.getName()).log(Level.SEVERE, null, ex);
        }

        //Connect to server and set up input stream reader
        Socket socket = new Socket("codd.uwcs.co.uk", 6667);
        InputStreamReader streamReader = new InputStreamReader(socket.getInputStream());
        BufferedReader bufferedReader = new BufferedReader(streamReader);
        String inputLine = null;

        identifyBot(socket);

        //Do nothing until message from NickServ and then join initial channel
        while ((inputLine = bufferedReader.readLine()) != null) {
            System.out.println(inputLine);
            if (extractUsername(inputLine).equals("NickServ")) {
                joinInitialChannel(socket);
                break;
            }
        }
        
        LocalDate ld = null;
        LocalDate od = new LocalDate(DateTimeZone.UTC);
        String oldDate = od.toString("dd/MM/yyyy");
        
        //Start monitoring message
        while ((inputLine = bufferedReader.readLine()) != null) {
            monitorInputStream(socket, inputLine, dbConn);
            ld = new LocalDate(DateTimeZone.UTC);
            ld.toString("dd/MM/yyyy");
            if(!oldDate.equals(ld.toString("dd/MM/yyyy"))){
                oldDate = ld.toString("dd/MM/yyyy");
                findTodaysToDos(dbConn, socket, ld);
            }
            
        }

        //Close everything
        bufferedReader.close();
        streamReader.close();
        socket.close();
        try {
            dbConn.close();
        } catch (SQLException ex) {
            Logger.getLogger(ToDoBot.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    static Connection createDatabaseConnection() throws ClassNotFoundException, SQLException {
        Class.forName("org.sqlite.JDBC");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:todoList.db");
        return conn;
    }

    //Identify with server (sets username and name)
    static void identifyBot(Socket socket) throws IOException {
        PrintWriter writer;
        writer = new PrintWriter(socket.getOutputStream(), true);
        writer.println("NICK " + botName);
        System.out.println("NICK " + botName);
        writer.println("USER " + botName + " server host :To-do list bot");
        System.out.println("USER " + botName + " server host :To-do list bot");
    }

    //Channels for the bot to join when it connects to the server
    static void joinInitialChannel(Socket socket) throws IOException {
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
        writer.println("JOIN #Shai");
        System.out.println("JOIN #Shai");
    }

    //Read input line. If line is a PING, return PONG, else check message type.
    static void monitorInputStream(Socket socket, String inputLine, Connection dbConn) throws IOException, SQLException {
        if (inputLine.startsWith("PING :codd.uwcs.co.uk")) {
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            System.out.println("PING :codd.uwcs.co.uk");
            writer.println("PONG :codd.uwcs.co.uk");
            System.out.println("PONG :codd.uwcs.co.uk");
        } else {
            ParsedMessage parsedMessage = checkMessageType(inputLine, socket);
            if (parsedMessage.message != null) {
                if (parsedMessage.type.equals("INVITE")) {
                    respondToInvite(parsedMessage, socket);
                } else if (parsedMessage.type.equals("PRIVMSG")) {
                    checkForCommands(parsedMessage, socket, dbConn);
                }
            }
        }
    }

    static void findTodaysToDos(Connection dbConn, Socket socket, LocalDate ld) throws SQLException, IOException {
        Statement stat = dbConn.createStatement();
        ResultSet rs = stat.executeQuery("SELECT * FROM ToDoList WHERE date='"+ld.toString("dd/MM/yyyy")+"'");
        StringBuilder todoMessage = null;
        
        while(rs.next()) {
            todoMessage.append("PRIVMSG ").append(rs.getString("user")).append(" :To do today: ").
                    append(rs.getString("time")).append(" ").append(rs.getString("todo"));
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            writer.println(todoMessage.toString());
            System.out.println("Todo: "+todoMessage);
        }
    }
            
    //Check whether input line contains a PRIVMSG or INVITE
    static ParsedMessage checkMessageType(String inputLine, Socket socket) throws IOException {
        ParsedMessage parsedMessage = new ParsedMessage();
        System.out.println(inputLine);

        if (checkPRIVMSG(inputLine)) {
            //If PRIVMSG, extract channel and message
            parsedMessage = extractData(inputLine, " PRIVMSG ");
        } else if (checkINVITE(inputLine)) {
            //If INVITE, extract channel and message
            parsedMessage = extractData(inputLine, " INVITE ");
        }

        return parsedMessage;
    }

    //Creates a new parsed message object containing details about any messages in the input line
    static ParsedMessage extractData(String inputLine, String msgType) {
        ParsedMessage parsedMessage = new ParsedMessage();

        String username = extractUsername(inputLine);
        String channel = extractChannel(inputLine, msgType);
        String message = extractMessage(inputLine, channel);

        if (username != null && channel != null) {
            parsedMessage.username = username;
            parsedMessage.channel = channel;
            parsedMessage.message = message;
        }

        parsedMessage.type = msgType.replace(" ", "");

        return parsedMessage;
    }

    //Check if input line is a PRIVMSG
    static boolean checkPRIVMSG(String inputLine) {
        boolean priv = false;
        /*Pattern privPattern = Pattern.compile(":.+!.+@.+ PRIVMSG #");
        Matcher privMatch = privPattern.matcher(inputLine);
        if(privMatch.matches()){
        System.out.println("Private message");
        }*/
        if (inputLine.contains(" PRIVMSG ")) {
            priv = true;
        }
        return priv;
    }

    //Check if input line contains an INVITE
    static boolean checkINVITE(String inputLine) {
        boolean inv = false;
        if (inputLine.contains(" INVITE ")) {
            inv = true;
        }
        return inv;
    }

    //Return the username part of the input line (who sent the message)
    static String extractUsername(String inputLine) {
        StringBuilder username = new StringBuilder();
        int i = 1;
        if (!inputLine.startsWith(":codd.uwcs.co.uk")) {
            while (inputLine.charAt(i) != '!' && i < inputLine.length()) {
                username.append(inputLine.charAt(i));
                i++;
            }
            System.out.println("Username: " + username);
        } else {
            username.append(":codd");
            username.replace(0, 0, inputLine);
        }
        return username.toString();
    }

    //Return the channel part of the input line (where the input came from)
    static String extractChannel(String inputLine, String checkFor) {
        StringBuilder channel = new StringBuilder();

        int index = inputLine.indexOf(checkFor);
        index += checkFor.length();

        while (inputLine.charAt(index) != ' ') {
            channel.append(inputLine.charAt(index));
            index++;
        }
        System.out.println("Channel: " + channel);
        return channel.toString();
    }

    //Return the message part of the input line
    static String extractMessage(String inputLine, String channel) {
        StringBuilder message = new StringBuilder();

        int index = inputLine.indexOf(channel);
        index += channel.length() + 2;

        while (index < inputLine.length()) {
            message.append(inputLine.charAt(index));
            index++;
        }

        System.out.println("Message: " + message);
        return message.toString();

    }

    //If bot is invited into a channel check if invitee had permissions.
    //If invitee does not have permissions, forward request to a user who does
    static void respondToInvite(ParsedMessage pm, Socket socket) throws IOException {
        PrintWriter writer = null;
        if (!pm.username.equalsIgnoreCase("shai")) {
            StringBuilder checkWithBoss = new StringBuilder("PRIVMSG ").append("Shai").append(" :").
                    append("Permission to join ").append(pm.message).append(" (").append(pm.username).append(")");
            writer = new PrintWriter(socket.getOutputStream(), true);

            writer.println(checkWithBoss);
            System.out.println(checkWithBoss);
        } else {
            writer = new PrintWriter(socket.getOutputStream(), true);
            writer.println("JOIN " + pm.message);
            System.out.println("JOIN " + pm.message);
        }
    }

    //Check each message for a bot command or request.
    //Messages to the bot can begin with '~' or '[botname]: '
    //Call respondToCommands if user has permissions, else just call respondToAlias
    static void checkForCommands(ParsedMessage pm, Socket socket, Connection dbConn) throws IOException, SQLException {
        boolean possibleCommand = true;
        if (pm.message.startsWith("~")) {
            pm.message = pm.message.substring(1);
        } else if (pm.message.startsWith(botName + ": ")) {
            pm.message = pm.message.substring(botName.length() + 2);
        } else {
            possibleCommand = false;
        }

        if (possibleCommand) {
            if (pm.username.equalsIgnoreCase("shai")) {
                if (!respondToCommands(pm, socket)) {
                    respondToAlias(pm, socket, dbConn);
                }
            } else {
                respondToAlias(pm, socket, dbConn);
            }
        }
    }

    //Sets up bot's response to commands given by users who have permission
    //Commands currently include part, join and quit.
    static boolean respondToCommands(ParsedMessage pm, Socket socket) throws IOException {
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
        boolean command = true;
        if (pm.message.startsWith("Leave")) {
            if (pm.message.equals("Leave")) {
                writer.println("PART " + pm.channel + " :Bye bye");
                System.out.println("PART " + pm.channel + " :Bye bye");
            } else {
                String channelToLeave = pm.message.subSequence(pm.message.indexOf("Leave") + 6, pm.message.length()).toString();
                writer.println("PART " + channelToLeave + " :Bye bye");
                System.out.println("PART " + channelToLeave + " :Bye bye");
            }
        } else if (pm.message.equals("Quit")) {
            writer.println("QUIT :coo :(");
            System.out.println("QUIT :coo :(");
        } else if (pm.message.startsWith("Join")) {
            String channelToJoin = pm.message.subSequence(pm.message.indexOf("Join") + 5, pm.message.length()).toString();
            writer.println("JOIN " + channelToJoin);
            System.out.println("JOIN " + channelToJoin);
        } else {
            command = false;
        }

        return command;
    }

    //Method for dealing with possible user requests.
    //Requests currently include hi, coo, flip and todo
    static void respondToAlias(ParsedMessage pm, Socket socket, Connection dbConn) throws IOException, SQLException {
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
        String message = pm.message;
        StringBuilder reply = null;
        if (message.startsWith("todotoday")){
            reply = privateReply(pm, dbConn);
        } else {     
            reply = new StringBuilder("PRIVMSG ").append(pm.channel).append(" :").append(pm.username).append(": ");

            if (message.startsWith("Hi") || message.startsWith("hi")) {
                reply.append("Hi!");
            } else if (message.startsWith("coo")) {
                reply.append("coo!");
            } else if (message.startsWith("flip")) {
                System.out.println("Flip!");
                String flip = flipACoin();
                reply.append(flip);
            } else if (message.startsWith("todo") || message.startsWith("Todo") || message.startsWith("ToDo")) {
                ParsedTodoCommand toDo = null;
                try {
                    toDo = newTodo(pm, dbConn);
                } catch (SQLException ex) {
                    Logger.getLogger(ToDoBot.class.getName()).log(Level.SEVERE, null, ex);
                    System.out.println("Hitting here");
                }
                if (toDo.message.equals(todoFormatError)) {
                    reply.append(toDo.message);
                } else {
                    reply.append("New todo: ").append(toDo.message).append(" stored!");
                }
            } else {
                reply.append("Not a valid command.");
            }
        }
        writer.println(reply.toString());
        System.out.println(reply.toString());
    }

    //To avoid flooding channels, the ~todotoday response is sent in a PM to the user
    //The method finds all of the to-dos set to be done today by the given user
    static StringBuilder privateReply(ParsedMessage pm, Connection dbConn) throws SQLException {
        Statement stat = dbConn.createStatement();
        LocalDate ld = new LocalDate(DateTimeZone.UTC);
        String todaysDate = ld.toString("dd/MM/yyyy");
        
        StringBuilder reply = new StringBuilder("PRIVMSG ");
        reply.append(pm.username).append(" :Your to-dos for today are: ");
        
        stat.execute("SELECT * FROM ToDoList WHERE user='"+pm.username+"' AND date='"+todaysDate+"'");
        
        ResultSet rs = stat.getResultSet();
        while(rs.next()) {
            reply.append("@").append(rs.getString("time")).append(" ").append(rs.getString("todo")).append("   ");
        }
                
        return reply;    
    }
    
    //Creates a new todo object if a todo is found.
    //Saves the new todo into the database.
    static ParsedTodoCommand newTodo(ParsedMessage pm, Connection dbConn) throws SQLException {
        ParsedTodoCommand todo = new ParsedTodoCommand();
        todo.message = "";
        int index = pm.message.indexOf('@');

        if (index < 0) {
            todo.message = todoFormatError;
        } else {
            todo = parseDateTime(pm.message, index);
            if (todo.validDate || todo.validTime || todo.validDateTime) { //If the format of the date is correct, extract the to-do message
                todo.message = parseToDo(pm.message, index + 1, todo);
                //Check a todo is given
                String msgCheck = todo.message.replaceAll(" ", "");
                if (msgCheck.length() == 0) {
                    todo.message = todoFormatError;
                } else {
                    saveTodo(pm, todo, dbConn);
                }
            } else {
                todo.message = todoFormatError;
            }
        }
        return todo;
    }

    //Checks which todo format was used (time-date, date or time) and calls the correct todo parser
    //Index = location of '@' symbol
    static ParsedTodoCommand parseDateTime(String pm, int index) {
        //Take message and time-date/date
        ParsedTodoCommand pd = null;
        String todo = pm.substring(index);
        //Attempt to extract time-date or date
        Pattern datePattern = Pattern.compile("@\\d\\d/\\d\\d/\\d\\d\\d\\d");
        Pattern timePattern = Pattern.compile("@\\d\\d:\\d\\d");
        Pattern dateTimePattern = Pattern.compile("@\\d\\d/\\d\\d/\\d\\d\\d\\d\\s\\d\\d:\\d\\d");
        Matcher dateTimeMatcher = dateTimePattern.matcher(todo);
        Matcher dateMatcher = datePattern.matcher(todo);
        Matcher timeMatcher = timePattern.matcher(todo);

        if (dateTimeMatcher.find()) {
            pd = validateTimeDate(todo);
        } else if (dateMatcher.find()) {
            pd = validateDate(todo);
        } else if (timeMatcher.find()) {
            pd = validateTime(todo);
        } else {
            todo = todoFormatError;
        }

        System.out.println("pd.date: " + pd.date);
        System.out.println("pd.time: " + pd.time);
        System.out.println("pd.datetime: " + pd.dateTime);

        return pd;
    }

    //Called when a time and date for a todo are given.
    //Determines if the time and date passed are valid.
    static ParsedTodoCommand validateTimeDate(String todo) {
        ParsedTodoCommand pd = new ParsedTodoCommand();
        DateTime dt = new DateTime();
        DateTimeFormatter dayTimeFormatter = DateTimeFormat.forPattern("dd/MM/yyyy HH:mm");

        String date = todo.substring(1, 11);
        String time = todo.substring(12, 17);
        String dateTime = todo.substring(1, 17);

        try {
            dt = dayTimeFormatter.parseDateTime(dateTime);
            if (dt.compareTo(new DateTime()) >= 0) {
                pd.date = date;
                pd.time = time;
                pd.dateTime = dateTime;
                pd.validDateTime = true;
            }
        } catch (IllegalArgumentException e) {
            System.out.println("Time-Date format: " + e);
        }

        return pd;
    }

    //Called when only a date for a todo is given.
    //Determines if the date passed is valid. Sets the time for the todo as 12:00.
    static ParsedTodoCommand validateDate(String todo) {
        ParsedTodoCommand pd = new ParsedTodoCommand();
        DateTime dt = new DateTime();
        DateTimeFormatter dayFormatter = DateTimeFormat.forPattern("dd/MM/yyyy");
       
        String date = todo.substring(1, 11);

        try {
            dt = dayFormatter.parseDateTime(date);
            if (dt.compareTo(new DateTime()) >= 0) {
                pd.date = date;
                pd.time = "12:00";
                pd.dateTime = pd.date + " " + pd.time;
                pd.validDate = true;
            }
        } catch (IllegalArgumentException e) {
            System.out.println("Date format: " + e);
        }

        return pd;
    }

    //Called when only a time for a todo is given.
    //Determines if the time passed is valid. Sets the date as the current date.
    static ParsedTodoCommand validateTime(String todo) {
        ParsedTodoCommand pd = new ParsedTodoCommand();
        DateTime dt = new DateTime();
        DateTimeFormatter timeFormatter = DateTimeFormat.forPattern("HH:mm");
        LocalDate currentDate = new LocalDate();
        String time = todo.substring(1, 6);

        try {
            dt = timeFormatter.parseDateTime(time);
            MutableDateTime mdt = dt.toMutableDateTime();
            mdt.setYear(currentDate.getYear());
            mdt.setMonthOfYear(currentDate.getMonthOfYear());
            mdt.setDayOfMonth(currentDate.getDayOfMonth());

            if (mdt.compareTo(new DateTime()) > 0) {
                pd.time = time;
                pd.date = currentDate.toString(DateTimeFormat.forPattern("dd/MM/yy"));
                pd.dateTime = pd.date + " " + pd.time;
                pd.validTime = true;
            }
        } catch (IllegalArgumentException e) {
            System.out.println("Date format: " + e);
        }

        return pd;
    }

    //Extracts and returns the todo message
    static String parseToDo(String pm, int index, ParsedTodoCommand pd) {
        String todo = "";
        //Location of message will change depending on if a time has been included 
        if (pd.validDateTime) {
            todo = pm.substring(index + 17);
        } else if (pd.validDate) {
            todo = pm.substring(index + 11);
        } else if (pd.validTime) {
            todo = pm.substring(index + 6);
        } else {
            todo = todoFormatError;
        }

        System.out.println("todo: " + todo);

        return todo;
    }

    //Saves the todo in the database
    static void saveTodo(ParsedMessage pm, ParsedTodoCommand ptd, Connection conn) throws SQLException {
        PreparedStatement prep = conn.prepareStatement("INSERT INTO ToDoList VALUES (?, ?, ?, ?);");

        prep.setString(1, ptd.date);
        prep.setString(2, ptd.time);
        prep.setString(3, pm.username);
        prep.setString(4, ptd.message);
        prep.addBatch();

        conn.setAutoCommit(false);
        prep.executeBatch();
        conn.setAutoCommit(true);
    }

    //Request. 50/50 chance of returning Yes/No to a query
    static String flipACoin() {
        boolean flip = false;
        double prob = Math.random();
        if (prob >= 0.5) {
            flip = true;
        }

        String answer = "No";
        if (flip) {
            answer = "Yes";
        }
        System.out.println("Flip: " + answer);
        return answer;


    }

    //Class for storing the message received
    static class ParsedMessage {

        String username;    //Username of person who sent message
        String channel;     //Channel message received in
        String message;     //Message received
        String type;        //PRIVMSG or INVITE
        boolean validToDo;  //If message received is a to-do.

        void parsedMessage(String username, String channel, String message, String type, boolean validToDo) {
            this.username = username;
            this.channel = channel;
            this.message = message;
            this.type = type;
            this.validToDo = validToDo;
        }
    }

    //Class for storing the todo
    static class ParsedTodoCommand {

        boolean validDate;  //Is date valid 
        String date;        //Date provided
        boolean validTime;  //Is time-date valid
        String time;        //Time and date provided
        boolean validDateTime; //Is the time-date of valid formet
        String dateTime;    //Date-time provided
        String message;     //To-do message

        void parsedTodoCommand(boolean validDate, String date, boolean validTime, String time, boolean validDateTime, String dateTime, String message) {
            this.validDate = false;
            this.date = date;
            this.validTime = false;
            this.time = time;
            this.validDateTime = false;
            this.dateTime = dateTime;
            this.message = message;
        }
    }
}
