/**
 * @license
 * Copyright 2020 The FOAM Authors. All Rights Reserved.
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package foam.nanos.mrac;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Queue;
import java.util.HashMap;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.lang.ref.WeakReference;

import foam.core.X;
import foam.core.FObject;
import foam.core.FoamThread;
import foam.core.AbstractFObject;
import foam.dao.DAO;
import foam.dao.Journal;
import foam.dao.AbstractJournal;
import static foam.mlang.MLang.*;
import foam.dao.ProxyDAO;
import foam.dao.Sink;
import foam.dao.ArraySink;
import foam.dao.AbstractSink;
import foam.mlang.sink.GroupBy;
import foam.core.Identifiable;
import foam.box.Message;
import foam.box.RPCMessage;
import foam.lib.json.Outputter;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.io.OutputStreamWriter;
import java.io.InputStream;
import java.io.IOException;

import org.apache.commons.io.IOUtils;
import javax.servlet.http.HttpServletResponse;
import foam.lib.json.JSONParser;

import foam.box.RPCMessage;
import foam.box.HTTPReplyBox;
import foam.box.Message;
import foam.box.RPCReturnMessage;

import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.ByteBuffer;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;

import foam.nanos.mrac.quorum.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import foam.lib.ClusterPropertyPredicate;
import foam.nanos.logger.Logger;

import java.util.concurrent.ExecutorService; 
import java.util.concurrent.Executors;

// Make sure that this class sould only have one instance in single journal mode.
// In multiple journal mode. each JDAO will have it's own instance.
// can simple get put to MedusaMediator
// Make sure MM initial before initial of this class.
// TODO: refactor this class as DAO.
// TODO: refactor all clusterNode finding in the a DAO. provide better controller of MN.
public class MMJournal extends AbstractJournal implements Electable {

  private String serviceName;
  private QuorumService quorumService;
  private final Map<Long, ArrayList<ClusterNode>> groupToMN = new HashMap<Long, ArrayList<ClusterNode>>();
  private final List<ArrayList<ClusterNode>> groups  = new LinkedList<ArrayList<ClusterNode>>();
  private final List<ClusterNode> availableNodes = new LinkedList<ClusterNode>();
  // Default TIME_OUT is 5 second
  private final long TIME_OUT = 10000;

  // globalIndex should be unique in each filename.
  // One MMJournal instance can be shared by different DAO(Single Journal Mode).
  // Method: Replay will update this index.
  private volatile AtomicLong globalIndex = new AtomicLong(1);

  //Only record two entry for now.
  //TODO: need to initial parents.
  MedusaEntry parent1;
  MedusaEntry parent2;
  int hashIndex = 1;
  Object hashRecordLock = new Object();

  int hashQuorumSize = 2;
  boolean isHash = true;
  int quorumSize = 2;

  private ExecutorService pool = Executors.newFixedThreadPool(3);

  //TODO: check if this method is really threadsafe.
  private void updateHash(MedusaEntry parent) {
    synchronized ( hashRecordLock ) {
      if ( hashIndex == 1 ) {
        parent1 = parent;
        hashIndex = 2;
      } else {
        parent2 = parent;
        hashIndex =1;
      }
    }
  }

  Logger logger;
  private MMJournal(X x, String serviceName) {
    logger = (Logger) x.get("logger");
    if ( x == null ) throw new RuntimeException("Miss Context");
    this.serviceName = serviceName;
    initial(x);
  }

  Object initialLock = new Object();
  private void initial(X x) {
    synchronized ( initialLock ) {
      if ( isInitialized ) return;

      if ( x == null ) throw new RuntimeException("Context miss.");

      quorumService = (QuorumService) x.get("quorumService");

      if ( quorumService == null ) throw new RuntimeException("quorumService miss");

      quorumService.registerElectable(this);

      DAO clusterNodeDAO = (DAO) x.get("clusterNodeDAO");
      if ( clusterNodeDAO == null ) throw new RuntimeException("clusterNodeDAO miss");

      GroupBy groupToInstance = (GroupBy) clusterNodeDAO
        .where(EQ(ClusterNode.TYPE, ClusterNodeType.MN))
        .select(GROUP_BY(ClusterNode.GROUP, new ArraySink.Builder(getX()).build()));

      for ( Object key : groupToInstance.getGroups().keySet() ) {
        for ( Object value: ((ArraySink) groupToInstance.getGroups().get(key)).getArray() ) {
          ClusterNode clusterNode = (ClusterNode) value;
          if ( groupToMN.get(clusterNode.getGroup()) == null ) {
            groupToMN.put(clusterNode.getGroup(), new ArrayList<ClusterNode>());
          }
          groupToMN.get(clusterNode.getGroup()).add(clusterNode);
          availableNodes.add(clusterNode);
        }
      }

      for ( Long group : groupToMN.keySet() ) {
        groups.add(groupToMN.get(group));
      }

      nodeToSocketChannel = new HashMap<Long, SocketChannel>();
      readyToUseEntry = new HashMap<String, List<MedusaEntry>>();
      registerDAOs = new HashMap<String, DAO>();

      //TODO: Configure Very first two Index; Very Important
      parent1 = new MedusaEntry();
      parent1.setMyHash("aaaaaa");
      parent1.setMyIndex(-1L);

      parent2 = new MedusaEntry();
      parent2.setMyHash("bbbbbb");
      parent2.setMyIndex(0L);

      indexHashMap = new HashMap<Long, String>();
      indexHashMap.put(parent1.getMyIndex(), parent1.getMyHash());
      indexHashMap.put(parent2.getMyIndex(), parent2.getMyHash());

      isInitialized = true;

    }
  }


  private ArrayList removeMN(long group) {
    return groupToMN.remove(group);
  }

  // GlobalIndex should only set in replay.
  public void setGlobalIndex(Long index) {
    globalIndex.set(index);
  }

  //TODO: provide better to do this.
  Object lock = new Object();
  int robin = 0 ;
  public int nextRobin() {
    synchronized ( lock ) {
      if ( robin == 1000000 ) return ( robin = 0 );
      else return robin++;
    }
  }

  Object indexLock = new Object();
  public Long getGlobalIndex() {
    synchronized(indexLock) {
      return globalIndex.getAndIncrement();
    }
  }


  private static Map<String, MMJournal> journalMap = new HashMap<String, MMJournal>();

  public synchronized static MMJournal getMMjournal(X x, String serviceName) {
    if ( journalMap.get(serviceName) != null ) return journalMap.get(serviceName);
    journalMap.put(serviceName, new MMJournal(x, serviceName));
    return journalMap.get(serviceName);
  }

  public volatile boolean isVersion = true;
  private volatile boolean isInitialized = false;
  // We will remove synchronized key word in the DAO put.
  // This method has to be thread safe.
  // The method only work if FObject implement Identifiable.Identifiable
  // Version is used to allow we can make parallel call.
  // TODO: can we do this versioning code at the begnning of DAO?
  @Override
  public FObject put(X x, String prefix, DAO dao, FObject obj) {
    if (  quorumService.exposeState != InstanceState.PRIMARY 
        || isPrimary() == false ) throw new RuntimeException("Electing/Secondary");
    long myIndex = getGlobalIndex();

    // Get whole entry first to make sure threadsafe.
    MedusaEntry p1 = parent1;
    while ( ( p1 = parent1 ) == null ) {}
    MedusaEntry p2 = parent2;
    while ( ( p2 = parent2) == null ) {}
    Message msg =
      createMessage(
          p1.getMyIndex(),
          p1.getMyHash(),
          p2.getMyIndex(),
          p2.getMyHash(),
          myIndex,
          "put_",
          "p",
          null,
          prefix,
          obj
          );
    Outputter outputter = new Outputter(getX());

    String mn = outputter.stringify(msg);
    callMN(mn);
    return obj;
  }

  //TODO: remove after finish two very first parents.
  private FObject putParentHash(long myIndex, String hash, FObject randomFObject) {
    Message msg =
      createMessage(
          -1,
          null,
          -1,
          null,
          myIndex,
          "put_",
          "p",
          null,
          "TOP",
          randomFObject
          );
    return null;
  }

  @Override
  public FObject remove(X x, String prefix, DAO dao, FObject obj) {
    if (  quorumService.exposeState != InstanceState.PRIMARY 
        || isPrimary() == false ) throw new RuntimeException("Electing/Secondary");

    long myIndex = getGlobalIndex();
    // Get whole entry first to make sure threadsafe.
    MedusaEntry p1 = parent1;
    while ( ( p1 = parent1 ) == null ) {}
    MedusaEntry p2 = parent2;
    while ( ( p2 = parent2) == null ) {}
    Message msg =
      createMessage(
          p1.getMyIndex(),
          p1.getMyHash(),
          p2.getMyIndex(),
          p2.getMyHash(),
          myIndex,
          "remove_",
          "r",
          null,
          prefix,
          obj
          );
    Outputter outputter = new Outputter(getX());

    String mn = outputter.stringify(msg);
    callMN(mn);
    return obj;
  }


  private void callMN(String medusaEntry) {

    int index = nextRobin() % groups.size();
    int i = 0;
    int totalTry = groups.size();
    boolean isPersist = false;

    while ( i < totalTry ) {
      ArrayList<ClusterNode> nodes = groups.get(index);
      Object[] tasks = new Object[nodes.size()];

      for ( int j = 0 ; j < nodes.size() ; j++ ) {
        ClusterNode node = nodes.get(j);
        tasks[j] = new FutureTask<String>(new Sender(node.getIp(), node.getServicePort(), medusaEntry));
        //TODO: use threadpool.
        //new Thread((FutureTask<String>) tasks[j]).start();
        pool.execute(new Thread((FutureTask<String>) tasks[j]));
      }

      long endtime = System.currentTimeMillis() + TIME_OUT * (i + 1);
      int check = 0;
      int falseCheck = 0;
      boolean[] checks = new boolean[nodes.size()];
      Arrays.fill(checks, false);

      MedusaEntry p = null;

      //while ( System.currentTimeMillis() < endtime && Math.abs(check) < quorumSize ) {
      while ( Math.abs(check) < quorumSize ) {
        for ( int j = 0 ; j < tasks.length ; j++ ) {
          if ( checks[j] == false && ((FutureTask<String>) tasks[j]).isDone() ) {
            FutureTask<String> task = (FutureTask<String>) tasks[j];
            try {
              String response = task.get();
              //TODO: a bug, return message format wrong.
              Message responseMessage = (Message) getX().create(JSONParser.class).parseString(response);
              p = (MedusaEntry) ((RPCReturnMessage) responseMessage.getObject()).getData();
              if ( p instanceof MedusaEntry ) {
                check++;
              } else {
                falseCheck++;
              }
            } catch ( Exception e ) {
              //TODO: log error
              falseCheck++;
            } finally {
              checks[j] = true;
            }
          }
        }
      }

      if ( check >= quorumSize ) {
        isPersist = true;
        updateHash(p);
        break;
      }

      index++;
      i++;
    }



    // Important
    if ( isPersist == false ) {
      //TODO: shutdown the put method.
      throw new RuntimeException("MN do not work....");
    }

  }

  private Message createMessage(
      long globalIndex1,
      String hash1,
      long globalIndex2,
      String hash2,
      long myIndex,
      String method,
      String action,
      FObject old,
      String prefix,
      FObject nu
      ) {
    Message message = getX().create(Message.class);
    RPCMessage rpc = getX().create(foam.box.RPCMessage.class);
    //put or remove
    rpc.setName(method);
    MedusaEntry entry = getX().create(MedusaEntry.class);
    entry.setServiceName(serviceName);
    // p or r
    entry.setAction(action);
    entry.setGlobalIndex1(globalIndex1);
    entry.setHash1(hash1);
    entry.setGlobalIndex2(globalIndex2);
    entry.setHash2(hash2);
    entry.setMyIndex(myIndex);
    entry.setOld(old);
    entry.setNspecKey(prefix);
    entry.setNu(nu);
    Object[] args = {null, entry};
    rpc.setArgs(args);

    message.setObject(rpc);
    HTTPReplyBox replyBox = getX().create(HTTPReplyBox.class);
    message.getAttributes().put("replyBox", replyBox);
    return message;
      }

  private TcpMessage createListenMessage() {
    TcpMessage tcpMessage = new TcpMessage();
    tcpMessage.setServiceKey(serviceName);
    // TcpSocketChannelSinkBox replyBox = new TcpSocketChannelSinkBox();
    String replayBox = "{\"class\":\"foam.nanos.mrac.TcpSocketChannelSinkBox\"}";
    tcpMessage.getAttributes().put("replyBox", replayBox);
    tcpMessage.getAttributes().put("sessionId", "aaaa");
    RPCMessage rpc = new RPCMessage();
    rpc.setName("listen_");
    Object[] args = {null, "{\"class\":\"foam.nanos.mrac.TcpSocketChannelSink\"}", null};
    rpc.setArgs(args);
    tcpMessage.setObject(rpc);
    return tcpMessage;
  }

  //TODO: Modelling TcpSocketChannelSinkBox and remove this method.
  private String createListenMessageString(String serviceName, String sessionId) {
    // String ret = "{\"class\":\"foam.nanos.mrac.TcpMessage\",\"serviceKey\":\"" + serviceName + "\",\"attributes\":{\"replyBox\":{\"class\":\"foam.nanos.mrac.TcpSocketChannelSinkBox\"},\"sessionId\":\"" + sessionId + "\"},\"object\":{\"class\":\"foam.box.RPCMessage\",\"name\":\"listen_\",\"args\":[null,{\"class\":\"foam.nanos.mrac.TcpSocketChannelSink\"},null]}}";
    String ret = "{\"class\":\"foam.nanos.mrac.TcpMessage\",\"serviceKey\":\"" + serviceName + "\",\"attributes\":{\"replyBox\":{\"class\":\"foam.nanos.mrac.TcpSocketChannelSinkBox\"},\"sessionId\":\"" + sessionId + "\"},\"object\":{\"class\":\"foam.box.RPCMessage\",\"name\":\"listen_\",\"args\":[null,{\"class\":\"foam.nanos.mrac.TcpSocketChannelSink\"},null]}}";
    return ret;
  }


  private class Sender implements Callable<String> {
    private String ip;
    private String message;
    private int port;

    public Sender(String ip, int port, String message) {
      this.ip = ip;
      this.message = message;
      this.port = port;
    }

    public String call() throws Exception {
      HttpURLConnection conn = null;
      OutputStreamWriter output = null;
      InputStream input = null;

      try {
        URL url = new URL("Http", ip, port, "/service/" + serviceName);
        logger.info(url);

        conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");

        output =
          new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8);

        output.write(message);
        output.close();

        // check response code
        int code = conn.getResponseCode();
        if ( code != HttpServletResponse.SC_OK ) {
          throw new RuntimeException("Http server return: " + code);
        }

        byte[] buf = new byte[8388608];
        input = conn.getInputStream();

        int off = 0;
        int len = buf.length;
        int read = -1;
        while ( len != 0 && ( read = input.read(buf, off, len) ) != -1 ) {
          off += read;
          len -= read;
        }

        if ( len == 0 && read != -1 ) {
          throw new RuntimeException("Message too large.");
        }
        logger.info("success persist [ " + message + " ] entry into " + ip + ":" +  port);
        return  new String(buf, 0, off, StandardCharsets.UTF_8);
      } catch ( Exception e ) {
        logger.info("fail persist [ " + message + " ] entry into " + ip + ":" +  port + " error message: " + e);
        throw e;
      } finally {
        IOUtils.closeQuietly(output);
        IOUtils.closeQuietly(input);
        if ( conn != null ) {
          conn.disconnect();
        }
      }
    }
  }

  // Record SocketChannel for each node.
  private Map<Long, SocketChannel> nodeToSocketChannel;
  private Map<String, List<MedusaEntry>> readyToUseEntry;
  private Map<String, DAO> registerDAOs;

  //TODO: prvide a way to clear this map
  private Map<Long, String> indexHashMap;

  private final void initialReplay(X x) {
    //TODO: close all socketchannel.
    if ( nodeToSocketChannel != null ) {
      for ( Map.Entry<Long, SocketChannel> entry: nodeToSocketChannel.entrySet() ) {
        TCPNioServer.closeSocketChannel(entry.getValue());
      }

    }
    nodeToSocketChannel = new HashMap<Long, SocketChannel>();
  }

  public final void cleanConnection() {
    if ( nodeToSocketChannel != null ) {
      for ( Map.Entry<Long, SocketChannel> entry: nodeToSocketChannel.entrySet() ) {
        TCPNioServer.closeSocketChannel(entry.getValue());
      }
    }
  }

  public volatile boolean needReplay = true;
  public volatile boolean isReplaying = false;

  @Override
  public void replay(X x, DAO dao) {
    throw new RuntimeException("MMJournal do not support replay(x,DAO)");
  }

  // Make sure only replay once.
  private volatile boolean isReplayed = false;

  public synchronized void pullData(X x, long fromIndex, boolean isListen) {
    try {
      if ( isListen ) {
        initialListener(x);
      }
      initialReplay(x);
      List<MedusaEntry> entries = retrieveData(x, groupToMN, fromIndex);
      for ( MedusaEntry entry : entries ) {
        Outputter outputter = new Outputter(getX());
        String mn = outputter.stringify(entry);
      }
      logger.info("total entry receive in replay: " + entries.size());
      cacheOrMDAO(entries);
    } catch ( Exception e ) {
      throw new RuntimeException(e);
    }
  }

  // Get data start from global Index.
  public synchronized void updateData(X x, boolean isListen) {
    if ( needReplay == false ) return;

    try {
      isReplaying = true;
      logger.info("update with globalIndex: " + globalIndex.get());
      pullData(x , globalIndex.get(), isListen);
      isReplaying = false;
      needReplay = false;
    } catch ( Exception e ) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  public void replay(X x, String nspecKey, DAO dao) {

    // Disable put when doing replay to a dao.
    synchronized ( cacheOrMDAOLock ) {
      int count = 0;
      if ( registerDAOs.get(nspecKey) != null ) {
        throw new RuntimeException("can not replay duplicate dao: " + nspecKey);
      }

      registerDAOs.put(nspecKey, dao);
      if ( readyToUseEntry.get(nspecKey) == null ) {
        logger.info("No cached entry associated with: " + nspecKey);
        return;
      }
      // Load cached entries into DAO.
      for ( MedusaEntry obj : readyToUseEntry.get(nspecKey) ) {
        count++;
        if ( "p".equals(obj.getAction()) ) {
          //TODO: investigating on TransactionDAO.
          dao.put(obj.getNu());
        } else if ( "r".equals(obj.getAction()) ) {
          dao.remove(obj.getNu());
        } else {
          throw new RuntimeException("Do not have action inMedusaEntry");
        }
      }
      logger.info("Service: " + nspecKey + " replay " + count + " entries from Mediator");
      readyToUseEntry.remove(nspecKey);
    }
  }

  // Help method for text only
  public void printReadyTOUseEntry() {
    for ( Map.Entry<String, List<MedusaEntry>> entry : readyToUseEntry.entrySet() ) {
      for ( MedusaEntry obj : entry.getValue() ) {
        Outputter outputter = new Outputter(getX());
        String mn = outputter.stringify(obj);
      }
    }
  }


  private Object cacheOrMDAOLock = new Object();
  // This method only apply on secondary.
  // Only one thread can access this function at any give time. When instance is secondary.
  private void cacheOrMDAO(MedusaEntry entry) {
    cacheOrMDAO(entry, false);
  }

  private void cacheOrMDAO(MedusaEntry entry, boolean verifyhash) {
    synchronized ( cacheOrMDAOLock ) {
      // Data already in the MDAO.
      if ( entry.getMyIndex() < globalIndex.get() ) return;
      if ( entry.getMyIndex() != globalIndex.get() ) throw new RuntimeException("Wrong globalIndex [ expect=" + globalIndex + ", receive=" + entry.getMyIndex() + " ]");

      if ( registerDAOs.get(entry.getNspecKey()) != null ) {
        DAO dao = registerDAOs.get(entry.getNspecKey());
        try {
          if ( "p".equals(entry.getAction()) ) {
            //TODO: investigating on TransactionDAO.
            dao.put(entry.getNu());
          } else if ( "r".equals(entry.getAction()) ) {
            dao.remove(entry.getNu());
          } else {
            throw new RuntimeException("Do not have action inMedusaEntry");
          }
        } catch ( Exception e ) {
          logger.error("cacheOrMDAO error: " + e);
        }
      } else {
        if ( readyToUseEntry.get(entry.getNspecKey()) == null ) {
          readyToUseEntry.put(entry.getNspecKey(), new LinkedList<MedusaEntry>() );
        }
        List<MedusaEntry> entryList = readyToUseEntry.get(entry.getNspecKey());
        entryList.add(entry);
      }

      if ( verifyhash ) {
        try {
          //TODO: turn hash on.
          MessageDigest md = MessageDigest.getInstance("SHA-256");
          md.update(indexHashMap.get(entry.getGlobalIndex1()).getBytes(StandardCharsets.UTF_8));
          md.update(indexHashMap.get(entry.getGlobalIndex2()).getBytes(StandardCharsets.UTF_8));
          String myHash = MNJournal.byte2Hex(entry.getNu().hash(md));
          if ( ! myHash.equals(entry.getMyHash()) ) {
            logger.info("Invalid Hash: [ \n" + "expect Hash value: " + myHash + "\n" + "receive Hash value: " + entry.getMyHash() + "\n]");
            throw new RuntimeException("Invalid hash");
          }
        } catch ( Exception e ) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
      } 

      globalIndex.set(entry.getMyIndex() + 1L);
      updateHash(entry);
      //TODO: important clear this map
      indexHashMap.put(entry.getMyIndex(), entry.getMyHash());

      //TODO: update globalIndex and parent, varify hash.
      ////close hash for now to see the performance difference.
      //globalIndex.set(entry.getMyIndex() + 1L);
      //recordIndex = recordIndex + 1L;
      //updateHash(entry);
      //try {
      //  //TODO: turn hash on.
      //  MessageDigest md = MessageDigest.getInstance("SHA-256");
      //  md.update(indexHashMap.get(entry.getGlobalIndex1()).getBytes(StandardCharsets.UTF_8));
      //  md.update(indexHashMap.get(entry.getGlobalIndex2()).getBytes(StandardCharsets.UTF_8));
      //  String myHash = MNJournal.byte2Hex(entry.getNu().hash(md));
      //  logger.info(myHash);
      //  logger.info(entry.getMyHash());
      //  if ( ! myHash.equals(entry.getMyHash()) ) {
      //    throw new RuntimeException("hash invalid");
      //  }
      //  indexHashMap.put(entry.getMyIndex(), entry.getMyHash());
      //} catch ( Exception e ) {
      //  e.printStackTrace();
      //  throw new RuntimeException(e);
      //}
    }
  }

  private void cacheOrMDAO(List<MedusaEntry> entries) {
    synchronized ( cacheOrMDAOLock ) {
      for ( MedusaEntry entry :  entries ) {
        cacheOrMDAO(entry);
      }
      logger.info("replay finish: " + globalIndex.get());
    }
  }


  private final List<MedusaEntry> retrieveData(X x, Map<Long, ArrayList<ClusterNode>> groupToMN, long fromIndex) {

    // MedusaNode id to Bytebuffer.
    Map<Long, Map<Long, LinkedList<ByteBuffer>>> groupToJournal = new HashMap<Long, Map<Long, LinkedList<ByteBuffer>>>();
    Map<Long, List<MedusaEntry>> groupToEntry = new HashMap<Long, List<MedusaEntry>>();
    SocketChannel channel = null;
    for ( Map.Entry<Long, ArrayList<ClusterNode>> entry : groupToMN.entrySet() ) {
      long groupId = entry.getKey();
      Map<Long, LinkedList<ByteBuffer>> nodeToBuffers = new HashMap<Long, LinkedList<ByteBuffer>>();
      groupToJournal.put(groupId, nodeToBuffers);

      int count = 0;
      for ( ClusterNode node : entry.getValue() ) {
        try {
          channel = SocketChannel.open();
          channel.configureBlocking(true);
          InetSocketAddress address = new InetSocketAddress(node.getIp(), node.getSocketPort());
          //The system should wait for connection at here.
          boolean connectResult = channel.connect(address);

          if ( connectResult == false )
            throw new RuntimeException("Replay can not connect to: " + node.getId());

          nodeToSocketChannel.put(node.getId(), channel);
          logger.info(node.getId());
          nodeToBuffers.put(node.getId(), retrieveDataFromNode(x, channel, fromIndex));
          count++;
        } catch ( Exception e ) {
          logger.info("!!fail replay from : " + node.getId() + "-" + node.getHostName() + " exception message: " + e);
          TCPNioServer.closeSocketChannel(channel);
          throw new RuntimeException(e);
        } finally {
          try {
            if ( channel != null ) {
              channel.close();
              channel = null;
            }
          } catch ( IOException ie ) {
            logger.info(ie);
          }
        }
      }

      logger.info("replay from " + count + " medusa node");

      if ( count < quorumSize ) {
        throw new RuntimeException("Do not get data from enough MN");
      }

      groupToEntry.put(groupId, concatEntries(groupId, parseEntries(x, nodeToBuffers)));
    }

    return sortEntries(mergeEntries(groupToEntry));

  }

  private final LinkedList<ByteBuffer> retrieveDataFromNode(X x, SocketChannel channel, long fromIndex) throws IOException {
    LinkedList<ByteBuffer> buffers = new LinkedList<ByteBuffer>();
    ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
    TcpMessage replayInitialMessage = new TcpMessage();
    replayInitialMessage.setServiceKey("MNService");
    RPCMessage rpc = getX().create(RPCMessage.class);
    rpc.setName("replayFrom");
    Object[] args = { null, serviceName, fromIndex };
    rpc.setArgs(args);
    replayInitialMessage.setObject(rpc);

    Outputter outputter = new Outputter(x);
    String msg = outputter.stringify(replayInitialMessage);
    byte[] bytes = msg.getBytes(Charset.forName("UTF-8"));
    ByteBuffer ackBuffer = ByteBuffer.allocate(4 + bytes.length);
    ackBuffer.putInt(bytes.length);
    ackBuffer.put(bytes);
    ackBuffer.flip();
    channel.write(ackBuffer);
    // Waiting for ACK from MN.
    lengthBuffer.clear();

    // Start receiving.
    if ( channel.read(lengthBuffer) < 0 ) throw new RuntimeException("End of Stream");
    lengthBuffer.flip();

    int length = lengthBuffer.getInt();
    if ( length < 0 ) throw new RuntimeException("End of Stream");
    ByteBuffer firstBlockInfo = ByteBuffer.allocate(length);
    if ( channel.read(firstBlockInfo) < 0 ) throw new RuntimeException("End of Stream");

    firstBlockInfo.flip();
    String blockInfoString = new String(firstBlockInfo.array(), 0, length, Charset.forName("UTF-8"));
    BlockInfo blockInfo = (BlockInfo) getX().create(JSONParser.class).parseString(blockInfoString);
    logger.info(blockInfoString);
    while ( blockInfo.getEof() == false ) {
      if ( blockInfo.getAnyFailure() == true )
        throw new RuntimeException(blockInfo.getFailReason() + "\n" + blockInfo.getFailLine());

      lengthBuffer.clear();
      channel.read(lengthBuffer);
      lengthBuffer.flip();
      length = lengthBuffer.getInt();
      ByteBuffer readBuffer = ByteBuffer.allocate(length);
      channel.read(readBuffer);
      readBuffer.flip();
      buffers.add(readBuffer);

      lengthBuffer.clear();
      channel.read(lengthBuffer);
      lengthBuffer.flip();
      length = lengthBuffer.getInt();
      ByteBuffer nextBlockInfo = ByteBuffer.allocate(length);
      if ( channel.read(nextBlockInfo) < 0 ) throw new RuntimeException("End of Stream");
      nextBlockInfo.flip();
      blockInfoString = new String(nextBlockInfo.array(), 0, length, Charset.forName("UTF-8"));
      blockInfo = (BlockInfo) getX().create(JSONParser.class).parseString(blockInfoString);
      logger.info(blockInfoString);
    }


    if ( blockInfo.getAnyFailure() == true )
      throw new RuntimeException(blockInfo.getFailReason() + "\n" + blockInfo.getFailLine());

    if ( blockInfo.getTotalEntries() > 0 ) {
      lengthBuffer.clear();
      channel.read(lengthBuffer);
      lengthBuffer.flip();
      length = lengthBuffer.getInt();
      ByteBuffer readBuffer = ByteBuffer.allocate(length);
      // int l = 0;
      // while ( l < length ) {
      //   int i = channel.read(readBuffer)
      //   l = l + i;
      // }
      while ( readBuffer.hasRemaining() ) {
        channel.read(readBuffer);
      }
      readBuffer.flip();
      buffers.add(readBuffer);
    }
    // int totalBlock = filePacket.getTotalBlock();

    // for ( int i = 0 ; i < totalBlock ; i++ ) {
    //   lengthBuffer.clear();

    //   channel.read(lengthBuffer);
    //   lengthBuffer.flip();
    //   int length = lengthBuffer.getInt();
    //   ByteBuffer readBuffer = ByteBuffer.allocate(length);

    //   channel.read(readBuffer);
    //   readBuffer.flip();
    //   buffers.add(readBuffer);
    // }


    return buffers;
  }

  private final Map<Long, List<MedusaEntry>> parseEntries(X x, Map<Long, LinkedList<ByteBuffer>> nodeTojournal) {
    Map<Long, List<MedusaEntry>> ret = new HashMap<Long, List<MedusaEntry>>();
    Map<Long, Integer> count = new HashMap<Long, Integer>();
    for ( Map.Entry<Long, LinkedList<ByteBuffer>> entry2: nodeTojournal.entrySet() ) {
      long clusterNodeId = entry2.getKey();
      List<MedusaEntry> medusaEntrys = new LinkedList<MedusaEntry>();
      ret.put(clusterNodeId, medusaEntrys);

      byte[] carryOverBytes = null;
      int carryOverLength = -1;
      byte[] carryOverLengthBytes = null;
      byte[] lengthBytes = null;

      for ( ByteBuffer buffer : entry2.getValue() ) {

        if ( carryOverLengthBytes != null ) {
          int remain = 4 - carryOverLengthBytes.length;
          byte[] unreadLengthBytes = new byte[remain];
          buffer.get(unreadLengthBytes);
          lengthBytes = new byte[4];
          System.arraycopy(carryOverLengthBytes, 0, lengthBytes, 0, carryOverLengthBytes.length);
          System.arraycopy(unreadLengthBytes, 0, lengthBytes, carryOverLengthBytes.length, unreadLengthBytes.length);
          carryOverLengthBytes = null;
        }

        if ( carryOverBytes != null ) {
          int remain = carryOverLength - carryOverBytes.length;
          byte[] remainBytes = new byte[remain];
          buffer.get(remainBytes);
          byte[] entryBytes = new byte[carryOverBytes.length + remainBytes.length];
          System.arraycopy(carryOverBytes, 0, entryBytes, 0, carryOverBytes.length);
          System.arraycopy(remainBytes, 0, entryBytes, carryOverBytes.length, remainBytes.length);
          String entry = new String(entryBytes, 0, carryOverLength, Charset.forName("UTF-8"));
          carryOverBytes = null;
          carryOverLength = -1;

          MedusaEntry medusaEntry = (MedusaEntry) getX().create(JSONParser.class).parseString(entry);
          if ( medusaEntry == null ) throw new RuntimeException("parse error: " + entry);
          medusaEntrys.add(medusaEntry);
        }
        while ( buffer.hasRemaining() ) {

          if ( buffer.remaining() < 4 ) {
            carryOverLengthBytes = new byte[buffer.remaining()];
            buffer.get(carryOverLengthBytes);
            carryOverBytes = null;
            carryOverLength = -1;
            break;
          }

          int length;
          if ( lengthBytes != null ) {
            length = ByteBuffer.wrap(lengthBytes).getInt();
            lengthBytes = null;
          } else {
            length = buffer.getInt();
          }

          if ( length > buffer.remaining() ) {
            carryOverBytes = new byte[buffer.remaining()];
            carryOverLength = length;
            buffer.get(carryOverBytes);
            carryOverLengthBytes = null;
          } else {
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            String entry = new String(bytes, 0, length, Charset.forName("UTF-8"));

            MedusaEntry medusaEntry = (MedusaEntry) getX().create(JSONParser.class).parseString(entry);
            medusaEntrys.add(medusaEntry);

            carryOverBytes = null;
            carryOverLength = -1;
            carryOverLengthBytes = null;
          }
        }
      }

      //TODO: record last entry for each node.
    }

    return ret;
  }

  // Verify entries from same group. And concat them into list.
  private final List<MedusaEntry> concatEntries(long groupId, Map<Long, List<MedusaEntry>> nodeToEntry) {
    Map<Long, Integer> entryCount = new HashMap<Long, Integer>();
    Map<Long, ArrayList<MedusaEntry>> entryRecord = new HashMap<Long, ArrayList<MedusaEntry>>();

    for ( Map.Entry<Long, List<MedusaEntry>> entry2: nodeToEntry.entrySet() ) {
      for ( MedusaEntry entry : entry2.getValue() ) {
        if ( entryCount.get(entry.getMyIndex()) == null ) {
          entryCount.put(entry.getMyIndex(), 1);
          ArrayList<MedusaEntry> list = new ArrayList<MedusaEntry>();
          list.add(entry);
          entryRecord.put(entry.getMyIndex(), list);
        } else {
          //TODO: check if hash equal.
          entryCount.put(entry.getMyIndex(), entryCount.get(entry.getMyIndex()) + 1);
          entryRecord.get(entry.getMyIndex()).add(entry);
        }
      }
    }

    List<MedusaEntry> entryList = new LinkedList<MedusaEntry>();
    logger.info("entryCount size: " + entryCount.size());

    for ( Map.Entry<Long, Integer> entry : entryCount.entrySet() ) {
      if ( isHash ) {
        //TODO: re-implementation.
        ArrayList<MedusaEntry> collectEntries = entryRecord.get(entry.getKey());
        Map<String, Integer> quorumHashRecord = new HashMap<String, Integer>();
        String errorMessage = "";
        boolean hashSuccess = false;
        for ( MedusaEntry e : collectEntries ) {
          errorMessage = "\n" + e.toString(); 
          if ( quorumHashRecord.get(e.getMyHash()) != null ) {
            quorumHashRecord.put(e.getMyHash(), quorumHashRecord.get(e.getMyHash()).intValue() + 1);
          } else {
            quorumHashRecord.put(e.getMyHash(), 1);
          }

          if ( quorumHashRecord.get(e.getMyHash()).intValue() >= hashQuorumSize ) {
            entryList.add(e);
            hashSuccess = true;
            break;
          }
        }
        if ( hashSuccess == false ) {
          logger.debug("start>>> \nCan not find enough number of HashValue from: " + errorMessage + "\n>>>>end");
        }
      } else {
        if ( entry.getValue().intValue() >= quorumSize ) entryList.add(entryRecord.get(entry.getKey()).get(0));
      }
    }
    logger.info("From groupId: " + groupId + " [ receive:" + entryCount.size() + ", accept:" + entryList.size() + " ]");
    return entryList;
  }

  private final List<MedusaEntry> sortEntries(List<MedusaEntry> entries) {
    logger.info("before sort: " + entries.size());
    Collections.sort(entries, new SortbyIndex());
    logger.info("after sort: " + entries.size());
    return entries;
  }

  private final List<MedusaEntry> mergeEntries(Map<Long, List<MedusaEntry>> groupToEntry) {

    List<MedusaEntry> ret = new LinkedList<MedusaEntry>();
    for ( Map.Entry<Long, List<MedusaEntry>> entry : groupToEntry.entrySet() ) {
      ret.addAll(entry.getValue());
    }
    return ret;
  }

  private class SortbyIndex implements Comparator<MedusaEntry> {

    public int compare(MedusaEntry a, MedusaEntry b) {
      Long obj1 = a.getMyIndex();
      Long obj2 = b.getMyIndex();

      return obj1.compareTo(obj2);
    }
  }


  // Each group has its own processor
  private class Processor extends FoamThread {

    private final Selector selector;
    private volatile boolean isRunning;
    private final Long groupId;
    private final Queue<SocketChannel> acceptedSocketChannels;
    private final List<SocketChannel> registerChannels;
    private X x;
    public CountDownLatch countDownLatch;


    public Processor(X x, Long groupId) throws IOException {
      super(groupId.toString(), true);
      this.x = x;
      this.groupId = groupId;
      this.selector = Selector.open();
      isRunning = true;
      acceptedSocketChannels = new LinkedBlockingQueue<SocketChannel>();
      registerChannels = new LinkedList<SocketChannel>();
      countDownLatch = new CountDownLatch(1);
    }

    public boolean acceptSocketChannel(SocketChannel channel) {
      if ( isRunning && acceptedSocketChannels.offer(channel) ) {
        wakeup();
        return true;
      }
      return false;
    }

    public void wakeup() {
      selector.wakeup();
    }

    public void close() {
      isRunning = false;
      selector.wakeup();
    }

    private void configureNewConnections() {
      SocketChannel socketChannel = acceptedSocketChannels.poll();

      while ( isRunning && socketChannel != null ) {
        SelectionKey key = null;
        try {
          socketChannel.configureBlocking(false);
          socketChannel.socket().setSoLinger(false, -1);
          socketChannel.socket().setTcpNoDelay(true);

          key = socketChannel.register(selector, SelectionKey.OP_CONNECT);
          registerChannels.add(socketChannel);

        } catch ( IOException e ) {
          logger.info("Fail to listen from: " + socketChannel.socket().getInetAddress());
          TCPNioServer.removeSelectionKey(key);
          TCPNioServer.hardCloseSocketChannel(socketChannel);
        }
        socketChannel = acceptedSocketChannels.poll();
      }
    }

    private void select() {
      try {
        selector.select();
        Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

        while ( isRunning  && iterator.hasNext() ) {
          SelectionKey key = iterator.next();
          iterator.remove();

          if ( key.isConnectable() ) {
            SocketChannel channel=(SocketChannel)key.channel();
            logger.info("success to lisetn from: " + channel.socket().getInetAddress());
            if(channel.isConnectionPending()){
              channel.finishConnect();
            }
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
          }

          // if ( key.isValid() == false ) {
          //   TCPNioServer.removeSelectionKey(key);
          //   continue;
          // }

          if ( key.isWritable() ) {
            initialRequest(key);
          }

          if ( key.isReadable() ) {
            processRequest(key);
          }
        }
      } catch ( IOException e ) {
        logger.info(e);
      }
    }

    private void processRequest(SelectionKey key) {
      SocketChannel socketChannel = null;
      try {
        socketChannel = (SocketChannel) key.channel();
        ByteBuffer lenBuf = ByteBuffer.allocate(4);

        int rc = socketChannel.read(lenBuf);
        if ( rc < 0 ) throw new IOException("End of Stream");

        int messageLen = -1;
        if ( lenBuf.remaining() == 0 ) {
          lenBuf.flip();
          messageLen = lenBuf.getInt();
          lenBuf.clear();
        }
        if ( messageLen < 0 ) throw new IOException("Len error: " + messageLen);

        ByteBuffer msgBuf = ByteBuffer.allocate(messageLen);
        if ( socketChannel.read(msgBuf) < 0 ) throw new IOException("End of Stream");

        String msgStr = null;
        if ( msgBuf.remaining() == 0 ) {
          msgBuf.flip();
          msgStr = new String(msgBuf.array(), 0, messageLen, Charset.forName("UTF-8"));
          msgBuf.clear();
        }

        logger.info("broadcast: " + msgStr);

        FObject msg = getX().create(JSONParser.class).parseString(msgStr);

        if ( msg == null ) {
          logger.info("Failed to parse request: " + msg);
          return;
        }

        if ( ! ( msg instanceof MedusaEntry ) ) {
          logger.info(msgStr);
          return;
        }

        MedusaEntry entry = (MedusaEntry) msg;

        processEntry(groupId, entry);
      } catch ( IOException e ) {
        logger.info(e);
        TCPNioServer.removeSelectionKey(key);
        TCPNioServer.hardCloseSocketChannel(socketChannel);
      }
    }

    private void initialRequest(SelectionKey key) {
      SocketChannel socketChannel = null;
      try {
        socketChannel = (SocketChannel) key.channel();
        String tcpMsgStr = createListenMessageString(serviceName, "");
        // Outputter outputter = new Outputter(x);
        // String tcpMsgStr = outputter.stringify(tcpMsg);
        byte[] bytes = tcpMsgStr.getBytes(Charset.forName("UTF-8"));
        ByteBuffer tcpMsgBuf = ByteBuffer.allocate(4 + bytes.length);
        tcpMsgBuf.putInt(bytes.length);
        tcpMsgBuf.put(bytes);
        tcpMsgBuf.flip();
        socketChannel.write(tcpMsgBuf);
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
      } catch ( IOException e ) {
        e.printStackTrace();
        TCPNioServer.removeSelectionKey(key);
        TCPNioServer.hardCloseSocketChannel(socketChannel);
      }
    }

    @Override
    public void run() {
      try {
        while ( isRunning ) {
          configureNewConnections();
          select();
        }

      } catch ( Exception e ) {
        e.printStackTrace();
      } finally {

        for ( SocketChannel channel : registerChannels ) {
          TCPNioServer.hardCloseSocketChannel(channel);
        }
        for ( SelectionKey key : selector.keys() ) {
          TCPNioServer.removeSelectionKey(key);
        }

        SocketChannel socketChannel = acceptedSocketChannels.poll();
        while ( socketChannel != null ) {
          TCPNioServer.hardCloseSocketChannel(socketChannel);
          socketChannel = acceptedSocketChannels.poll();
        }
        logger.info("All processor close.");
        countDownLatch.countDown();
      }
    }
  }

  private volatile EntryLoader loader;

  private void initialListener(X x) throws IOException {
    logger.info("initialListerner");
    if ( processorsMap != null ) {
      for ( Map.Entry<Long, Processor> entry : processorsMap.entrySet() ) {
        entry.getValue().close();
      }
    }
    cachedEntry = new HashMap<Long, Map<MedusaEntry, Integer>>();
    for ( Long group : groupToMN.keySet() ) {
      cachedEntry.put(group, new HashMap<MedusaEntry, Integer>());
    }

    entryCounts = new HashMap<Long, Map<Long, Integer>>();
    for ( Long group : groupToMN.keySet() ) {
      entryCounts.put(group, new HashMap<Long, Integer>());
    }

    entryRecords = new HashMap<Long, Map<Long, ArrayList<MedusaEntry>>>();
    for ( Long group : groupToMN.keySet() ) {
      entryRecords.put(group, new HashMap<Long, ArrayList<MedusaEntry>>());
    }

    cachedEntryMap = new HashMap<Long, MedusaEntry>();

    processorsMap = new HashMap<Long, Processor>();

    for ( Map.Entry<Long, ArrayList<ClusterNode>> group : groupToMN.entrySet() ) {
      Long groupId = group.getKey();
      Processor processor = new Processor(x, groupId);
      processor.start();
      for ( ClusterNode node : group.getValue() ) {
        //TODO: create socketChannel;
        InetSocketAddress address = new InetSocketAddress(node.getIp(), node.getSocketPort());
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(false);
        channel.connect(address);
        // if ( processor.acceptSocketChannel(channel) ) throw new RuntimeException("Socket connection error");
        processor.acceptSocketChannel(channel);
      }
      processorsMap.put(groupId, processor);
    }
    if ( loader != null ) loader.close();
    loader = new EntryLoader(x, new CountDownLatch(1));
    loader.start();
  }

  public boolean stopReplay() {
    if ( processorsMap != null ) {
      for ( Map.Entry<Long, Processor> entry : processorsMap.entrySet() ) {
        entry.getValue().close();
        try {
          entry.getValue().countDownLatch.await();
        } catch ( Exception e ) {
          logger.error(e);
        }
      }
    }
    processorsMap = null;
    return true;
  }

  public boolean stopListener(X x) {
    if ( loader != null ) {
      loader.close();
      try {
        loader.countDownLatch.await();
      } catch ( Exception e ) {
        logger.error(e);
      }
    }
    loader = null;

    if ( processorsMap != null ) {
      for ( Map.Entry<Long, Processor> entry : processorsMap.entrySet() ) {
        entry.getValue().close();
      }
    }

    cachedEntry = null;
    cachedEntryMap = null;
    processorsMap = null;

    return true;
  }

  // Map<groupId, Map<MedusaEntry, count>>
  private Map<Long, Map<MedusaEntry, Integer>> cachedEntry;

  private Map<Long, Map<Long, Integer>> entryCounts;
  private Map<Long, Map<Long, ArrayList<MedusaEntry>>> entryRecords;

  // GroupId to Processor map.
  private Map<Long, Processor> processorsMap;
  // Map<myIndex, MedusaEntry>.
  private Map<Long, MedusaEntry> cachedEntryMap;
  private Object cachedEntryMapLock = new Object();

  //TODO: add consensus
  private void processEntry(Long groupId, MedusaEntry entry) {
    if ( quorumService.exposeState == InstanceState.PRIMARY ) return;
    Map<MedusaEntry, Integer> entryCount = cachedEntry.get(groupId);
    //TODO: provide a way to clear cache.
    synchronized ( entryCount ) {
      //TODO: rewrite this method.
      if ( entry.getMyIndex() < globalIndex.get() ) return;
      if ( entryCount.get(entry) == null ) {
        addEntryIntoCachedMap(entry);
        return;
      } else if ( entryCount.get(entry) != null ) {
        return;
      }
      if ( entryCount.get(entry) == null ) {
        entryCount.put(entry, new Integer(1));
      } else if ( entryCount.get(entry) == 1 ) {
        addEntryIntoCachedMap(entry);
      } else {
        //ignore.
      }
    }
  }

  // private void processEntry1(Long groupId, MedusaEntry entry) {
  //   if ( quorumService.exposeState == InstanceState.PRIMARY ) return;
  //   Map<Long, Integer> entryCount = entryCounts.get(groupId);
  //   synchronized ( entryCount ) {
  //     if ( entry.getMyIndex() < globalIndex.get() ) return;
  //     if ( entryCount.get(entry.getMyIndex()) == null ) {
  //       entryCount.set(entry.getMyIndex(), new Integer(1));
  //       entryRecords.put(entry.getMyIndex(), (new ArrayList<MedusaEntry>()).add(entry));
  //       addEntryIntoCachedMap(entry);
  //     } else {

  //     }
  //   }
  // }


  private void addEntryIntoCachedMap(MedusaEntry entry) {
    synchronized ( cachedEntryMapLock ) {
      if ( entry.getMyIndex() < globalIndex.get() ) return;
      cachedEntryMap.put(entry.getMyIndex(), entry);
    }
  }

  //TODO: create a queue.
  private void processEntryFromCachedMap(Long index) {
    if ( cachedEntryMap.get(index) != null ) {
      logger.info("queeueque Index: " + index);
      synchronized ( cachedEntryMapLock ) {
        cacheOrMDAO(cachedEntryMap.get(index));
        cachedEntryMap.remove(index);
      }
    }
  }


  private void shutdwonProcessors() {
    if ( this.processorsMap != null ) {
      for ( Map.Entry<Long, Processor> entry : this.processorsMap.entrySet() ) {
        entry.getValue().close();
      }
    }
    this.processorsMap = null;
  }

  private class EntryLoader extends FoamThread {

    private volatile boolean isRunning = true;
    private X x;
    public final CountDownLatch countDownLatch;

    public EntryLoader(X x, CountDownLatch countDownLatch) {
      super("entryLoader");
      this.x = x;
      this.countDownLatch = countDownLatch;
    }

    public void close() {
      isRunning = false;
    }

    @Override
    public void run() {
      while ( needReplay = false && isRunning ) {}
      logger.info("replay finish. entryloader start");
      while ( isRunning ) {
        try {
          if ( quorumService.exposeState == InstanceState.PRIMARY ) continue;
          processEntryFromCachedMap(globalIndex.get());
        } catch ( Exception e ) {
          e.printStackTrace();
        }
      }
      countDownLatch.countDown();
    }
  }

  // This method only use for test.
  // This method hold when the minimal ByteBuffer is greater than 4,
  // and a entry should not be accross more than two ByteBuffer.
  private final void printAll(X x, Map<Long, Map<Long, LinkedList<ByteBuffer>>> groupTojournal) {
    for ( Map.Entry<Long, Map<Long, LinkedList<ByteBuffer>>> entry1 : groupTojournal.entrySet() ) {
      long groupId = entry1.getKey();
      for ( Map.Entry<Long, LinkedList<ByteBuffer>> entry2: entry1.getValue().entrySet() ) {
        long clusterNodeId = entry2.getKey();
        byte[] carryOverBytes = null;
        int carryOverLength = -1;
        byte[] carryOverLengthBytes = null;
        byte[] lengthBytes = null;

        for ( ByteBuffer buffer : entry2.getValue() ) {

          if ( carryOverLengthBytes != null ) {
            int remain = 4 - carryOverLengthBytes.length;
            byte[] unreadLengthBytes = new byte[remain];
            buffer.get(unreadLengthBytes);
            lengthBytes = new byte[4];
            System.arraycopy(carryOverLengthBytes, 0, lengthBytes, 0, carryOverLengthBytes.length);
            System.arraycopy(unreadLengthBytes, 0, lengthBytes, carryOverLengthBytes.length, unreadLengthBytes.length);
            int length = ByteBuffer.wrap(lengthBytes).getInt();
          }

          if ( carryOverBytes != null ) {
            int remain = carryOverLength - carryOverBytes.length;
            byte[] remainBytes = new byte[remain];
            buffer.get(remainBytes);
            byte[] entryBytes = new byte[carryOverBytes.length + remainBytes.length];
            System.arraycopy(carryOverBytes, 0, entryBytes, 0, carryOverBytes.length);
            System.arraycopy(remainBytes, 0, entryBytes, carryOverBytes.length, remainBytes.length);
            String entry = new String(entryBytes, 0, carryOverLength, Charset.forName("UTF-8"));
            carryOverBytes = null;
            carryOverLength = -1;
          }
          while ( buffer.hasRemaining() ) {
            int length;

            if ( lengthBytes != null ) {
              length = ByteBuffer.wrap(lengthBytes).getInt();
              lengthBytes = null;
            } else {
              length = buffer.getInt();
            }

            if ( buffer.remaining() < 4 ) {
              carryOverLengthBytes = new byte[buffer.remaining()];
              buffer.get(carryOverLengthBytes);
              carryOverBytes = null;
              carryOverLength = -1;
            } else if ( length > buffer.remaining() ) {
              carryOverBytes = new byte[buffer.remaining()];
              carryOverLength = length;
              buffer.get(carryOverBytes);
              carryOverLengthBytes = null;
            } else {
              byte[] bytes = new byte[length];
              buffer.get(bytes);
              String entry = new String(bytes, 0, length, Charset.forName("UTF-8"));
              logger.info(entry);
              carryOverBytes = null;
              carryOverLength = -1;
              carryOverLengthBytes = null;
            }
          }
        }
      }
    }
  }

  private volatile InstanceState currentState = InstanceState.ELECTING;
  public synchronized void primary(X x) {
    this.updateData(x, false);
    currentState = InstanceState.PRIMARY;
    logger.info("start primary: " + serviceName);
  }

  public boolean isPrimary() {
    return currentState == InstanceState.PRIMARY;
  }

  public synchronized void secondary(X x) {
    updateData(x, true);
    currentState = InstanceState.SECONDARY;
    logger.info("start secondary: " + serviceName);
  }

  public boolean isSecondary() {
    return currentState == InstanceState.SECONDARY;
  }

  public synchronized void leaveSecondary() {
    currentState = InstanceState.ELECTING;
    logger.info("leaveSecondary");
    stopReplay();
    cleanConnection();
    stopListener(null);
    needReplay = true;
    logger.info("clear connection finish");
  }

  public synchronized void leavePrimary() {
    currentState = InstanceState.ELECTING;
    cleanConnection();
    needReplay = true;
  }

  //TODO: remove this method after model this class.
  public int hashCode() {
    return java.util.Objects.hash(serviceName);
  }

  //TODO: remove this method after model this class.
  public boolean equals(Object o) {
    if ( o == null ) return false;
    if ( this == o ) return false;
    if ( this.getClass() != o.getClass() ) return false;

    MMJournal other = (MMJournal) o;
    return other.serviceName == this.serviceName;
  }
}