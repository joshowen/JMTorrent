import java.util.*;
import java.nio.*;
import java.net.*;
import java.io.*;
import java.security.*;
/**
 * 
 * @author jowen
 * @author mvoswinkel
 * 
 * Threaded client to connect to tracker and download a file.
 */
public class mybtclient {
	/* Global variables */
	int maxUploads = 20;
	int maxDownloads = 2;
	
	/* Bit set which tracks which pieces client has */
	Vector<Integer> no_piece = new Vector<Integer>();
	
	/* Boolean array of chunks downloaded */
	boolean[] chunks;
	
	/* Vector containing peer HashMaps */
	Vector peers = new Vector();
	
	/* Polling intertval */
	long interval;
	
	/* Peer ID */
	private String p_id;
	
	/* Helper for torrents */
	private TorrentFileHandler torrent_file_handler;
	
	/* Object containing useful torrent information */
	private TorrentFile torrent_file;
	
	/* Path to torrent */
	String filepath;
	
	/* Destination */
	String destination;
	
	/* Location of tracker */
	URL tracker_URL;
	
	/* Connections to tracker */
	BufferedReader i_stream;
	OutputStream o_stream;
	Socket tracker_sock;
	
	/* ServerSocket to accept requests */
	ServerSocket ss;
	
	/* Total downloaded */
	int totDownloaded;
	
	/* Ammt previously downloaded */
	int prevDownloaded;
	
	/* Total uploaded */
	int totUploaded;
	
	/*
	 * File pointer to the file we will write to.  We used random access file
	 * so we can write anywhere in the file.  Great for concurrent uploads 
	 * from different peers.
	 */
	RandomAccessFile file;
	
	/* Keep track of Uploaders and Downloaders */
	List<Uploader> uploaders = new LinkedList<Uploader>();
	List<Downloader> downloaders = new LinkedList<Downloader>();
	
	/* Threads to keep track of uploaders and downloaders */
	UploadTracker ut = new UploadTracker();
	DownloadTracker dt = new DownloadTracker();
	TrackerUpdate tu = new TrackerUpdate();
	
	/**
	 * Calls  ThreadedTrackerClient, passes Args
	 * @param args String Array, containing path to torrent file, and path to file location.
	 */
	public static void main(String[] args){
		mybtclient c = new mybtclient(args);
	}

	/**
	 * Connects to tracker, and unbencodes the hashmap response
	 * @param i_stream InputStram
	 * @param o_stream OutputStream
	 */
	private void contactTracker(BufferedReader i_stream, OutputStream o_stream){
		Bencoder bencoder = new Bencoder();
		String line, temp;
		try
		{
			line = i_stream.readLine();
			temp = line;
			/* Read to the last line, skips HTTP header info */
			while(line != null)
			{
				temp = line;
				line = i_stream.readLine();
			}
			/* Unbencode the tracker response and store as a hashmap */
			HashMap response = bencoder.unbencodeDictionary(temp.getBytes());
			
			/* Fill the peers vector with peer hashmaps */
			interval = Long.parseLong(response.get("interval").toString())/2;
			//System.out.println(interval);
			peers = (Vector)response.get("peers");
		}
		catch(IOException e){ System.err.println(e); }
	}

	/**
	 * Opens TorrentFile, checks that its valid
	 * @param filepath Path to Torrent File.
	 */
	private void OpenTorrent(String filepath)
	{	
		if ((torrent_file = torrent_file_handler.openTorrentFile(filepath)) == null)
		{
			System.err.print("Error: There was a problem when unencoding the file \"" 
						+ filepath + "\". File may be missing or corrupted.");
			return;
		}
	}

	/**
	 * Generates PID 
	 *
	 */
	private void GeneratePID()
	{
		byte[] bytes = new byte[20];
		bytes.toString();
		for(int i = p_id.length(); i < 15; i ++)
		{
			p_id = "-".concat(p_id);
		}
		p_id = "jm352".concat(p_id);
	}

	/**
	 * Returns URL of Tracker
	 * @return Tracker URL
	 */
	private URL GetTrackerURL()
	{
		try{
			return new URL(torrent_file.tracker_url);
		}
		catch(MalformedURLException e){ System.err.println(e); }
		return null;
	}
	
	/**
	 * Sends event to BitTorrent tracker
	 * @param event integer, 0 = updating; 1 = started; 2 = stopped; 3 = completed;
	 */
	private void SendEventToTracker(int event){
		String http_req;

		http_req = "/announce?info_hash=" 
			+ torrent_file.info_hash_as_url 
			+ "&peer_id=" + ToolKit.makeHTTPEscaped(p_id) 
			+ "&port=" + ss.getLocalPort() 
			+ "&uploaded=" + totUploaded 
			+ "&downloaded=" + (totDownloaded - prevDownloaded) 
			+ "&left=" + (torrent_file.file_length-totDownloaded);
		switch(event){
		case 0:
			break;
		case 1:
			http_req = http_req.concat("&event=started");
			break;
		case 2:
			http_req = http_req.concat("&event=stopped");
			break;
		case 3:
			http_req = http_req.concat("&event=completed");
			break;
		}
		http_req = "GET ".concat(http_req.concat(" HTTP/1.1\r\n\r\n"));
		try{
			o_stream.write(http_req.getBytes());
		}catch(Exception e){e.printStackTrace();}
	}
	
	/**
	 * Function creates a bitfield from existing data. This opens up the file
	 * given in the parameters and checks each piece area against an empty
	 * byte array. If it's empty it is an absent piece.
	 * 
	 * This works beautifully due to how we write our file. Because we check
	 * the piece against the SHA-1 Hash BEFORE we write it, there is no issue
	 * when we DO write that piece.
	 */
	private void makeBitfield(){
		int size = torrent_file.file_length/torrent_file.piece_length+1;
		chunks = new boolean[size];
		byte[] compare_to = new byte[torrent_file.piece_length];
		try{
			for(int i = 0; i < size; i++){
				byte[] compare = new byte[torrent_file.piece_length];
				synchronized(file){
					file.read(compare);
				}
				if(Arrays.equals(compare, compare_to)){
					no_piece.add(i);
				}
				else{
					chunks[i] = true;
					if(i == size-1){
						totDownloaded += torrent_file.file_length - torrent_file.piece_length * (i);
					}
					else{
						totDownloaded += torrent_file.piece_length;
					}
				}
			}
			//System.out.println("no_piece = " + no_piece.toString());
		}
		catch(Exception e){ e.printStackTrace(); }
	}
	
	/**
	 * @author mvoswink
	 * @param piece
	 * 
	 * Broadcasts a newly received piece to all peers if they are completely conected to the client
	 */
	private void BroadcastHaveToPeers(int piece){
		ByteBuffer n_piece = ByteBuffer.allocate(4);
		n_piece.putInt(piece);
		for(int i = 0; i < uploaders.size(); i++){
			if(uploaders.get(i).handshaken)
				uploaders.get(i).sendMsg(5, 4, n_piece.array());
		}
		for(int i = 0; i < downloaders.size(); i++){
			if(downloaders.get(i).handshaken)
				downloaders.get(i).sendMsg(5, 4, n_piece.array());
		}
	}
	
	/**
	 * Function which parses information out of torrent file, and processes it, thus creating download threads.
	 * @param args String Array containing the Torrent File location and the File location
	 */
	private mybtclient(String[] args){
		
		/*Check to make sure there are 2 arguments, requires torrent file and destination file*/
		if(args.length < 2)
		{
			System.out.println("Too few arguments!\n");
			return;
		}
		else
		{
			filepath = args[0];
			destination = args[1];
		}
		
		torrent_file_handler = new TorrentFileHandler();
		p_id = Integer.toString(new Random().nextInt());
		GeneratePID();
		OpenTorrent(filepath);
		
		tracker_URL = GetTrackerURL();
		
		try{
			file = new RandomAccessFile(destination,"rw");
			//System.out.println("len " + file.length());
		}catch(Exception e){e.printStackTrace();}
		
		makeBitfield();
		if(no_piece.size()==0){
			System.out.println("Download already completed, Seeding!");
		}
		prevDownloaded = totDownloaded;
		
		try
		{
			/* Connect to tracker and get response */
			tracker_sock = new Socket(tracker_URL.getHost(), tracker_URL.getPort());
			
			tracker_sock.setSoTimeout(6000);
			
		}
		catch(Exception e){e.printStackTrace();System.exit(1);}		

		/* Find an acceptable port to listen on */
		int port = 6899;
		boolean portFound=false;
		while(!portFound && port>6891){
			try{
				ss = new ServerSocket(port);
				portFound=true;
			}catch(Exception e){}
			port--;
		}
		/* If no port found, exit */
		if(!portFound){
			System.out.println("Acceptable port not found, exiting.");
			System.exit(1);
		}
		try{
			i_stream = new BufferedReader(new InputStreamReader(tracker_sock.getInputStream()));
			o_stream = tracker_sock.getOutputStream();
		}catch(Exception e){}
		
		/* Start the uploadtracker, downloadtracker, and trackerupdate threads */
		tu.start();
		dt.start();
		ut.start();
		
		/* Wait for user input for controlling the system */
		Scanner in = new Scanner(System.in);
		while(true){
			System.out.println("Please enter a command:");
			String input = in.nextLine();
			System.out.print("\n\n\n\n\n");
			if(input.equals("stats")){
				System.out.println("\t\t\tSystem Statistics");
				System.out.println("Bytes Uploaded: " + totUploaded);
				System.out.println("Bytes Downloaded: " + totDownloaded);
				System.out.println("Peers downloading from: " + downloaders.size());
				System.out.println("Peers uploading to: " + uploaders.size());
			}else if(input.equals("config")){
				System.out.println("\t\t\tSystem Configuration");
				System.out.println("Max download threads: " + maxDownloads);
				System.out.println("Max upload threads: " + maxUploads);
			}else if(input.equals("stop")){
				SendEventToTracker(2);
				ut.suspend();
				dt.suspend();
				tu.suspend();
			}else if(input.equals("resume")){
				SendEventToTracker(1);
				ut.resume();
				dt.resume();
				tu.resume();
			}else if(input.equals("exit")){
				SendEventToTracker(2);
				System.exit(0);
			}else{
				System.out.println("That is not an option");
				System.out.println();
				System.out.println("Valid options:");
				System.out.println("stats\tSystem statistics");
				System.out.println("config\tSystem configuration");
				System.out.println("stop\tStop the download");
				System.out.println("resume\tResume the download");
				System.out.println("exit\tExit the program");
			}
		}
	}
	
	/**
	 * @author mvoswinkel
	 * 
	 * Thread that updates tracker
	 */
	class TrackerUpdate extends Thread{
		public void run(){
			try{
				SendEventToTracker(1);
				contactTracker(i_stream, o_stream);
				while(true){
					sleep(interval*1000);
					SendEventToTracker(0);
				}
			}
			catch(Exception e){e.printStackTrace();}
		}
	}
	
	/**
	 * 
	 * @author jowen
	 * @author mvoswinkel
	 * 
	 * Thread that handles downloading of files
	 */
	class Downloader extends Thread{
		boolean downloading = true;
	    boolean is_choked = true;
	    boolean am_interested = false;
	    boolean peer_choking = true;
	    boolean peer_interested = false;
	    boolean handshaken = false;
	    boolean[] peer_has = new boolean[chunks.length];
	    /* Outgoing Message Queue for this thread */
	    LinkedList<byte[]> outQueue = new LinkedList<byte[]>();
	    /* Incoming Message Queue for this thread */
	    LinkedList<Message> inQueue = new LinkedList<Message>();
	    /*
	     * Set the initial chunk to 0, so it will start from the beginning and try
	     * to find the next undownloaded chunk sequentially
	    */
	    int chunk=0;
	    Socket s;
	    /*
	     * Used Data**putStreams so we could use .readInt,.writeByte,etc after we 
	     * discovered Java is BigEndian
	     */
	    DataOutputStream os;
		DataInputStream is;
		/* Make the downloader easy to reference from children */
		Downloader down = this;
		/* Create listener,sender, and keepalive threads */
		Listener list = new Listener();
		Sender send = new Sender();
		KeepAlive keep = new KeepAlive();
		HashMap p;
		
		/**
		 * Announces self to other peer, gets response
		 * @param pstrlen Length of Protocol Header
		 * @param pstr Protcol Header
		 * @param info_hash InfoHash from Torrent File
		 * @param peer_id PeerId (Random)
		 */
		private void handshake(int pstrlen, String pstr, byte[] info_hash, String peer_id){
			 /* Create a ByteBuffer the size of the handshake */
			 ByteBuffer bb = ByteBuffer.allocate(49+pstrlen);
			 byte[] byteArr = new byte[8];
			 byte by = (byte)pstrlen;
			 try{
				 /* Build the handshake using a ByteBuffer */
				 bb.put(by);
				 bb.put(pstr.getBytes());
				 bb.put(byteArr);
				 bb.put(info_hash);
				 bb.put(peer_id.getBytes());
				 /* Send out the handshake */
				 os.write(bb.array());
				 int size = is.read();
				 byte[] handshakeResponse = new byte[48+size];
				 /* Read in the handshake */
				 is.readFully(handshakeResponse);
				 handshaken = true;
			}catch(Exception e){e.printStackTrace();}
		}
		
		
		
		/**
		 * Creates a message requeiting part of a chunk.
		 * @param piece Integer specifying the chunk being requested
		 * @param begin Integer specifying the offset
		 */
		private void requestBlock(int piece,int begin,int size){
			ByteBuffer payload = ByteBuffer.allocate(12);
			payload.putInt(piece);
			payload.putInt(begin);
			payload.putInt(size);
			//System.out.println(this.getId() + " - Requesting Piece: " + piece + ", " + begin + ", " + size + "bytes.");
			sendMsg(13, 6, payload.array()); // send request for block;
		}
		
		
		/**
		 * Creates a message, and adds it to the outgoing Message Queue, for the Sender thread to send.
		 * @param prefix Mesage Prefix
		 * @param msgID Message ID
		 * @param payload Message Payload (If none, then null)
		 */
		private void sendMsg(int prefix, int msgID, byte[] payload){
			int off=0;
			ByteBuffer b = ByteBuffer.allocate(4+prefix);
			/*
			 * Create byte[] for the size of message
			 * Add the first 4 bytes (msg size)
			 */
			
			b.putInt(prefix);
			off=off+4;
			/* Add the message ID */
			b.put((byte)msgID);
			off++;
			/*
			 * If there is a payload (Anything other than size and message id) add it
			 * as BigEndian bytes
			 */
			if(payload != null){ 
				b.put(payload);
			}

			try{
				/* Throw it in the outQueu and notify anything waiting on the outQueue */
				synchronized(outQueue){
					outQueue.add(b.array());
					outQueue.notify();
				}
			}
			catch(Exception e){e.printStackTrace();}
		}
		
		/**
		 * Sends bitfield to peer
		 */
		private void sendBitfield(){
			int i = chunks.length - 1;
			int bbSize = chunks.length/8;
			if(chunks.length%8>0) bbSize++;
			ByteBuffer bb = ByteBuffer.allocate(bbSize);
			while(i >= 0){
				int val=0;
				if(chunks[i]) val += 128;									//1
				if(i-1 > -1 && chunks[i-1]==true)val += 64;		//2
				if(i-2 > -1 && chunks[i-2]==true)val += 32;		//3
				if(i-3 > -1 && chunks[i-3]==true)val += 16;		//4
				if(i-4 > -1 && chunks[i-4]==true)val += 8;		//5
				if(i-5 > -1 && chunks[i-5]==true)val += 4;		//6
				if(i-6 > -1 && chunks[i-6]==true)val += 2;		//7
				if(i-7 > -1 && chunks[i-7]==true)val += 1;	//8
				bb.put((byte)val);
				i -= 8;
			}
			parseBitfield(bb.array());
			sendMsg(1+bbSize,5,bb.array());
		}
		
		/**
		 * Sends block to peer
		 * @param index
		 * @param offset
		 * @param length
		 * @return
		 */
		private int sendBlock(int index, int offset, int length){
			int pointer_set;
			if(length > 16384){ return -1; }
			if(!peer_has[index]){ return -1; }
			try{
				byte[] piece = new byte[length];
				pointer_set = index*torrent_file.piece_length + offset;
				synchronized(file){
					file.seek(0);
					file.skipBytes(pointer_set);
					file.readFully(piece);
				}
				ByteBuffer bb = ByteBuffer.allocate(piece.length + 8);
				bb.putInt(index);
				bb.putInt(offset);
				bb.put(piece);
				sendMsg(1+bb.array().length, 7, bb.array());
				//System.out.println("sending block of lenth " + length);
				totUploaded = totUploaded+length;
				return 0;
			}
			catch(Exception e){ e.printStackTrace(); }
			return -1;
		}
		
		/**
		 * Parses peer's bitfield
		 * @param bfmsg
		 */
		private void parseBitfield(byte[] bfmsg){
			int i = 0;
			boolean[] bitfield = new boolean[bfmsg.length*8];
			
			//System.out.print(this.getId() + " - Bitfield: ");
			for( ;i < bfmsg.length; i++){
				int val = bfmsg[i] & 0xFF;
				if((val - 128) >= 0){
					bitfield[i*8] = true;
					//System.out.print("1");
					val -= 128;
				}
				else //System.out.print("0");
				if((val - 64) >= 0){
					bitfield[i*8+1] = true;
					//System.out.print("1");
					val -= 64;
				}
				else //System.out.print("0");
				if((val - 32) >= 0){
					bitfield[i*8+2] = true;
					//System.out.print("1");
					val -= 32;
				}
				else //System.out.print("0");
				if((val - 16) >= 0){
					bitfield[i*8+3] = true;
					//System.out.print("1");
					val -= 16;
				}
				else //System.out.print("0");
				if((val - 8) >= 0){
					bitfield[i*8+4] = true;
					//System.out.print("1");
					val -= 8;
				}
				else //System.out.print("0");
				if((val - 4) >= 0){
					bitfield[i*8+5] = true;
					//System.out.print("1");
					val -= 4;
				}
				else //System.out.print("0");
				if((val - 2) >= 0){
					bitfield[i*8+6] = true;
					//System.out.print("1");
					val -= 2;
				}
				else //System.out.print("0");
				if((val - 1) >= 0){
					bitfield[i*8+7] = true;
					//System.out.print("1");
					val -= 1;
				}
				//else System.out.print("0");
			}
			//System.out.println();
			peer_has = bitfield;
		}
		
		
		/**
		 * Thread that does the downloading of the file.  It waits for messages on the inputQueue and processes them.
		 * @param p HashMap with peer information
		 */
		Downloader(HashMap p){
			this.p=p;
		}
		
		/**
		 * Loops until the download is finished
		 */
		public void run(){
			try{
				/* Create a socket and input+output streams */
				s = new Socket(p.get("ip").toString(),Integer.parseInt(p.get("port").toString()));
				os = new DataOutputStream(s.getOutputStream());
				is = new DataInputStream(s.getInputStream());
				
				/* Shake hands with the peer */
				handshake(19, "BitTorrent protocol", torrent_file.info_hash_as_binary,
						 p_id);
				
				sendBitfield();
				
				//System.out.println(this.getId() + " - Hand shaken.");
				
				/* Start the send,keepalive, and listen threads */
				send.start();
				keep.start();
				list.start();
				
				while(downloading){
					/* Find another available chunk to download. Now with new and improved random! */
					Random rand = new Random();
					boolean found=false;
					synchronized(no_piece){
						while(true){
							int get_num = Math.abs(rand.nextInt());
							if(no_piece.size() > 0){
								chunk = no_piece.get(get_num%no_piece.size());
								no_piece.removeElement(chunk);
								found=true;
								break;
							}
							else{
								found = false;
								for(int i = 0; i < chunks.length; i++){
									if(chunks[i] == false){ 
										found = true;
										chunk = i;
									}
								}
								break;
							}
						}
					}
					if(found==false){
						break;//Download complete, check hash
					}else{
						/* More to download! */
						boolean moreToDL = true;
						
						/* Create a byte array that is the size of the piece to download */
						byte[] bytes;
						
						/*
						 * Make sure the chunk size is bigger than the file size. If not then
						 * make the byte array size the filesize 
						 */
						
						if(torrent_file.file_length < torrent_file.piece_length){
							bytes = new byte[torrent_file.file_length];
						}else{
							bytes = new byte[torrent_file.piece_length];
						}
						
						/* Start next piece if already connected. */
						if(!is_choked){
							/* Set the next request size */
							int reqSize2 = 16384;
							
							/* Check to make sure there is at least that much to download in the chunk
							 * If not then change the request size to be the remaining amount in the chunk
							 */
							if(torrent_file.file_length - chunk*torrent_file.piece_length < 16384){
								reqSize2 = torrent_file.file_length - chunk*torrent_file.piece_length;
								/* Changes the size of bytes if the piece is smaller than the normal piece size */
								bytes = new byte[reqSize2];
							}
							
							/* Request the new block of the chunk */
							requestBlock(chunk,0,reqSize2);
						}
						
						while(moreToDL){
							if(!s.isConnected()){
								no_piece.add(chunk);
								//System.out.println(this.getId() + " - Thread Closing!");
								/* End the threads */
								list.stop();
								keep.stop();
								send.stop();
								/* Removes connection notification */
								p.remove("connected");
								synchronized(downloaders){
									downloaders.remove(this);
									downloaders.notify();
								}
								break;
							}
							Message m;
							synchronized(inQueue){
								if(inQueue.isEmpty())inQueue.wait();
								m = inQueue.remove();
							}
							
							switch(m.messageType){
							case -1:
								//Other
								break;
							case 0:
								//choke
								//System.out.println("Choked!");
								is_choked = true;
								break;
							case 1:
								 //unchoke
								 //System.out.println("Unchoked!");
								 is_choked = false;
								 
								 int reqSize = 16384;
								 /* Check to see if the piece length is less than 2^14, set reqSize to piece length if true. */
								 if(torrent_file.piece_length<reqSize)reqSize = torrent_file.piece_length;
								 /* Check to see if the file length is less than 2^14, set reqSize to file length if true. */
								 if(torrent_file.file_length<reqSize)reqSize = torrent_file.file_length;
								 if(torrent_file.file_length - chunk*torrent_file.piece_length < 16384){
										reqSize = torrent_file.file_length - chunk*torrent_file.piece_length;
										/* Changes the size of bytes if the piece is smaller than the normal piece size */
										bytes = new byte[reqSize];
								}
								 requestBlock(chunk, 0, reqSize);
								 break;
							case 2:
								 //interested
								 am_interested = true;
								 break;
							case 3:
								 //not interested
								am_interested = false;
								 break;
							case 4:
								 //have
								peer_has[Integer.parseInt(m.message.toString())] = true;
								break;
							case 5:
								//bitfield
								parseBitfield(m.message);
								sendMsg(1, 2, null); //send interested message;
								break;
							case 6:
								//request
								sendBlock(m.block,m.offset,m.length);
								break;
							case 7:
								//Piece
								//System.out.println(this.getId() + " - Recieving piece...");
								/* Find the offset of the piece in the array */
								int fileOffset = m.block*torrent_file.piece_length + m.offset + m.message.length;
								
								/* Copy the message to the chunks byte array */
								System.arraycopy(m.message, 0, bytes, m.offset, m.message.length);
								
								/* Increase the offset */
								int offset = m.offset + m.message.length;
								
								/* Check if there is more file to download, if not, exit loop */
								if(!(offset >= torrent_file.piece_length || fileOffset>=torrent_file.file_length)){
									/* Set the next request size */
									int reqSize2 = 16384;
									
									/*
									 * Check to make sure there is at least that much to download in the chunk
									 * If not then change the request size to be the remaining amount in the chunk
									 */
									if(torrent_file.file_length-offset < 16384) reqSize2 = torrent_file.file_length-offset;
									
									//Request the new block of the chunk
									requestBlock(chunk,offset,reqSize2);
									break;
								}
								moreToDL=false;
								downloading = false;
								break;
							case 8:
								//Cancel
							case 9:
								//Port
							}
							
						}
						/*
						 * Check chunk's hash 
						 * Generate a hash of the chunk downloaded
						 */
						byte[] hash = new byte[20];
						MessageDigest sha = MessageDigest.getInstance("SHA-1");
						hash = sha.digest(bytes);
						/* Compare the hash generated with the hash that the tracker gave us */
						if(Arrays.equals(hash, (byte[])torrent_file.piece_hash_values_as_binary.get(chunk))){
							/* The Hash Matches, the chunk is downloaded, write to disk */
							if(chunks[chunk] == false){
								synchronized(chunks){
									chunks[chunk] = true;
								}
								synchronized(file){
									file.seek(0);
									file.skipBytes(chunk*torrent_file.piece_length);
									file.write(bytes);
								}
								/* Increment the total downloded */
								totDownloaded += bytes.length;
								BroadcastHaveToPeers(chunk);
							}
						}else{
							/* The hash did not match, try downloading it again */
							//System.out.println("The hash did not match, trying again!");
						}
					}
				}
				/*
				 * Chunk download complete 
				 * Tell the user the download is complete
				 */
				//System.out.println(this.getId() + " - Thread Closing!");
				/* End the threads */
				list.stop();
				keep.stop();
				send.stop();
				/* Removes connection notification */
				p.remove("connected");
				synchronized(downloaders){
					downloaders.remove(this);
					downloaders.notify();
				}
				this.stop();
			}catch(Exception e){e.printStackTrace();}
		}
		
		/**
		 * 
		 * @author jowen
		 * @author mvoswinkel
		 *
		 * Class listens for messages on the DataInputStream.  When incoming message is received, a Message object is created
		 * and its fields are filled.  If there is an additional payload other than the messageType, it is input.
		 */
		class Listener extends Thread{
			/**
			 * Function that listens for messages from DataInputStream and adds them to Queue
			 */
			public void run(){
				try{
					while(downloading){
						/* Get message size */
						byte[] len = new byte[4];
						is.read(len);
						int size = ToolKit.bigEndianBytesToInt(len, 0);
						
						if(size>0){//Size 0 is a keepalive
							Message m = new Message();
							/* Get message type */
							m.messageType = is.read();
							
							if(m.messageType>3){//1-3 don't have an additional payload
								/* Read the rest of the message */
								if(m.messageType!=7){
									m.message = new byte[size-1];
									is.readFully(m.message);
								}else{
									m.block=is.readInt();
									m.offset=is.readInt();
									m.message=new byte[size-9];
									is.readFully(m.message);
								}
							}
							synchronized(inQueue){
								/* Add message to the incoming queue, and notify anyone waiting on it */
								inQueue.add(m);
								inQueue.notify();
							}
							
						}else{
						}
					}
				}catch(Exception e){}
			}
		}
		
		
		
		
		/**
		 * 
		 * @author jowen
		 * @author mvoswinkel
		 * Thread Sends KeepAlive messages to the other peer every 30 seconds
		 */
		class KeepAlive extends Thread{
			/**
			 * Actual method that adds messages to the Queue, notifies it, then sleeps.
			 */
			public void run(){
				while(downloading){
					/* Generate a 4 byte array of 0s which is a keep alive message */
					byte[] b = new byte[4];
					/* Add the message to the outgoing Queue and notify it */
					synchronized(outQueue){
						outQueue.add(b);
						outQueue.notify();
					}
					try{
						/* Wait for 30 seconds and repeat */
						sleep(30000);
					}catch(Exception e){}
				}
			}
		}
		
		
				
		
		/**
		 * 
		 * @author jowen
		 * @author mvoswinkel
		 * 
		 * Thread sends messages to peer.  It sleeps until it is notified that there is something in the Queue, sends it, and sleeps again.
		 * 
		 */
		class Sender extends Thread{
			/**
			 * Function sends messages to output stream when they are in the Queue
			 */
			public void run(){
				try{
					while(downloading){
						byte[] b;
						/*
						 * If there is nothing in the queue, block until there is something
						 * and am notified.  Otherwise remove it and set it to the byte[] b
						 */
						synchronized(outQueue){
							if(outQueue.isEmpty())outQueue.wait();
							b = outQueue.remove();
						}
						/* Write b to the output stream and flush, then repeat */
						os.write(b);
						os.flush();
					}
				}catch(Exception e){}
			}			
		}
	}
/**
 * Keeps track of upload threads
 * @author jowen
 *
 */
	class UploadTracker extends Thread{
		public void run(){
			while(true){
				if(uploaders.size()<maxUploads){
					try{
						Socket s = ss.accept();
						//System.out.println("Got UL req from " + s.getRemoteSocketAddress());
						Uploader u = new Uploader(s);
						u.start();
						uploaders.add(u);
					}catch(Exception e){e.printStackTrace();}
				}else{
					try{
						synchronized(uploaders){
							uploaders.wait();
						}
					}catch(Exception e){}
				}
			}
		}
	}
	
/**
 * Keeps track of download threads
 * @author jowen
 *
 */
	class DownloadTracker extends Thread{
		/* Check if the download is finished */
		private boolean isFinished(){
			synchronized(no_piece){
				if(no_piece.size()==0)return true;
			}
			return false;
		}
		
		public void run(){
			if(!isFinished()){
				while(!isFinished()){
					/*
					 * Find a peer to connect to, currently set to just use the first peer returned
					 * Can easily be extended to download from multiple peers by removing the i<1.
					 */
					for(int i=0;i<peers.size();i++){
						/* Pull the peer HashMap */
						while(!isFinished()){
							if(downloaders.size()<maxDownloads){
								break;
							}else{
								synchronized(downloaders){
									try{
										downloaders.wait();
									}catch(Exception e){}
								}
							}
						}
						HashMap p = (HashMap)peers.get(i);
						/* Create a new downloader, and add it to the list */
						Downloader d = new Downloader(p);
						d.start();
						downloaders.add(d);
					}
				}
				/* Tell the tracker the download is complete */
				System.out.println("Download Complete!  Now seeding.");
				SendEventToTracker(3);
			}
		}
	}
	
	
	
/**
 * Handles upload requests
 * @author jowen
 * @author mvoswinkel
 *
 */
	class Uploader extends Thread{	
	    boolean am_choking = true;
	    boolean is_interested = false;
	    boolean peer_choking = true;
	    boolean peer_interested = false;
	    boolean handshaken = false;
	    boolean downloading = true;
	    
	    /* Outgoing Message Queue for this thread */
	    LinkedList<byte[]> outQueue = new LinkedList<byte[]>();
	    /* Incoming Message Queue for this thread */
	    LinkedList<Message> inQueue = new LinkedList<Message>();
	    
	    /*
	     * Set the initial chunk to 0, so it will start from the beginning and try
	     * to find the next unuploaded chunk sequentially
	    */
	    int chunk=0;
	    Socket s;
	    
	    /*
	     * Used Data(Out/In)putStreams so we could use .readInt,.writeByte,etc after we 
	     * discovered Java is BigEndian
	     */
	    DataOutputStream os;
		DataInputStream is;
		/* Make the uploader easy to reference from children */
		Uploader up = this;
		/* Create listener,sender, and keepalive threads */
		Listener list = new Listener();
		Sender send = new Sender();
		KeepAlive keep = new KeepAlive();		
		
		
		/**
		 * Revcs handshake request, respond
		 */
		private void handshake(int pstrlen, String pstr, byte[] info_hash, String peer_id){
			 /* Create a ByteBuffer the size of the handshake */
			 ByteBuffer bb = ByteBuffer.allocate(49+pstrlen);
			 byte[] byteArr = new byte[8];
			 byte by = (byte)pstrlen;
			 try{
				 int size = is.read();
				 URL u = new URL(torrent_file.tracker_url);
				 
				 if(s.getInetAddress().getHostName().toString().equals(u.getHost().toString())){
					/* Handshake is from tracker, ignore */
					byte[] b = new byte[47];
					is.readFully(b);
					//for(int i=0;i<b.length;i++){
						//System.out.print((char)b[i]);
					//}
					bb.put(by);
					bb.put(pstr.getBytes());
					bb.put(byteArr);
					bb.put(info_hash);
					bb.put(peer_id.getBytes());
					/* Send out the handshake */
					os.write(bb.array());
					
					/* End the threads */
					list.stop();
					keep.stop();
					send.stop();
					//System.out.println("exiting");
					synchronized(uploaders){
						uploaders.remove(this);
						uploaders.notify();
					}
					this.stop();
				 }
				 byte[] handshakeResponse = new byte[48+size];
				 /* Read in the handshake */
				 is.readFully(handshakeResponse);
				 
				 /* Build the handshake using a ByteBuffer */
				 bb.put(by);
				 bb.put(pstr.getBytes());
				 bb.put(byteArr);
				 bb.put(info_hash);
				 bb.put(peer_id.getBytes());
				 /* Send out the handshake */
				 os.write(bb.array());
				 handshaken = true;
				 
			}catch(Exception e){e.printStackTrace();}
		}
		
		private void sendBitfield(){
			int i = chunks.length - 1;
			int bbSize = chunks.length/8;
			if(chunks.length%8>0) bbSize++;
			ByteBuffer bb = ByteBuffer.allocate(bbSize);
			while(i >= 0){
				int val=0;
				if(chunks[i]) val += 128;									//1
				if(i-1 > -1 && chunks[i-1]==true)val += 64;		//2
				if(i-2 > -1 && chunks[i-2]==true)val += 32;		//3
				if(i-3 > -1 && chunks[i-3]==true)val += 16;		//4
				if(i-4 > -1 && chunks[i-4]==true)val += 8;		//5
				if(i-5 > -1 && chunks[i-5]==true)val += 4;		//6
				if(i-6 > -1 && chunks[i-6]==true)val += 2;		//7
				if(i-7 > -1 && chunks[i-7]==true)val += 1;	//8
				bb.put((byte)val);
				i -= 8;
			}
			//System.out.println("Sending bitfield");
			sendMsg(1+bbSize,5,bb.array());
		}
		/**
		 * Creates a message, and adds it to the outgoing Message Queue, for the Sender thread to send.
		 * @param prefix Mesage Prefix
		 * @param msgID Message ID
		 * @param payload Message Payload (If none, then null)
		 */
		private void sendMsg(int prefix, int msgID, byte[] payload){
			int off=0;
			ByteBuffer b = ByteBuffer.allocate(4+prefix);
			/* 
			 * Create byte[] for the size of message
			 * Add the first 4 bytes (msg size)
			 */
			b.putInt(prefix);
			off=off+4;
			/* Add the message ID */
			b.put((byte)msgID);
			off++;
			/*
			 * If there is a payload (Anything other than size and message id) add it
			 * as BigEndian bytes
			 */
			if(payload != null){ 
				b.put(payload);
			}

			try{
				/* Throw it in the outQueu and notify anything waiting on the outQueue */
				synchronized(outQueue){
					outQueue.add(b.array());
					outQueue.notify();
				}
			}
			catch(Exception e){e.printStackTrace();}
		}
		
		private int sendBlock(int index, int offset, int length){
			int pointer_set;
			if(length > 16384){ return -1; }
			try{
				byte[] piece = new byte[length];
				pointer_set = index*torrent_file.piece_length + offset;
				//System.out.println("path = " + destination + " piece length " + piece.length + " pointerset " + pointer_set + " length " + length + " filelen " + file.length());
				synchronized(file){
					file.seek(0);
					file.skipBytes(pointer_set);
					file.readFully(piece);
				}
				ByteBuffer bb = ByteBuffer.allocate(piece.length + 8);
				bb.putInt(index);
				bb.putInt(offset);
				bb.put(piece);
				sendMsg(1+bb.array().length, 7, bb.array());
				//System.out.println("sending block of lenth " + length);
				totUploaded = totUploaded+length;
				return 0;
			}
			catch(Exception e){ e.printStackTrace(); }
			return -1;
		}
		
		/**
		 * Thread that does the downloading of the file.  It waits for messages on the inputQueue and processes them.
		 * @param p HashMap with peer information
		 */
		Uploader(Socket s){
			this.s = s;
		}
		
		
		public void run(){
			try{
				/* Create a socket and input+output streams */
				os = new DataOutputStream(s.getOutputStream());
				is = new DataInputStream(s.getInputStream());
				
				/* Shake hands with the peer */
				handshake(19, "BitTorrent protocol", torrent_file.info_hash_as_binary,
						 p_id);
				
				/* Send the bitfield! */
				sendBitfield();
				
				/* Start the send,keepalive, and listen threads */
				send.start();
				keep.start();
				list.start();
				
				while(downloading){
					Message m;
					synchronized(inQueue){
						if(inQueue.isEmpty())inQueue.wait();
						m = inQueue.remove();
					}
					
					switch(m.messageType){
					case -1:
						//Other
						break;
					case 0:
						//choke
						am_choking = true;
						break;
					case 1:
						 //unchoke
						 am_choking = false;
						 break;
					case 2:
						 //interested
						 is_interested = true;
						 sendMsg(1,1,null);
						 am_choking = false;
						 break;
					case 3:
						//not interested
						is_interested = false;
						sendMsg(1,0,null);
						am_choking = true;
						break;
					case 4:
						//have
						break;
					case 5:
						//bitfield
						break;
					case 6:
						//request
						if(am_choking == false && is_interested == true){
							//System.out.println("Block " + m.block + " offset " + m.offset + " length "+ m.length);
							if(sendBlock(m.block, m.offset, m.length) != 0){
								//TODO close connection
							}
						}
						break;
					case 7:
						//Piece
						break;
					case 8:
						downloading = false;
						break;
					case 9:
						//Port
					}
				}
				/*
				 * Chunk upload complete
				 * 
				 * Tell the user the upload is complete
				 */
				System.out.println("Upload successful!");
				
				/* End the threads */
				list.stop();
				keep.stop();
				send.stop();
				synchronized(uploaders){
					uploaders.remove(this);
					uploaders.notify();
				}
				/* Kill the thread */
				this.stop();
			}catch(Exception e){e.printStackTrace();}
		}
		
		
		/**
		 * 
		 * @author jowen
		 * @author mvoswinkel
		 *
		 * Class listens for messages on the DataInputStream.  When incoming message is received, a Message object is created
		 * and its fields are filled.  If there is an additional payload other than the messageType, it is input.
		 */
		class Listener extends Thread{
			/**
			 * Function that listens for messages from DataInputStream and adds them to Queue
			 */
			public void run(){
				try{
					while(downloading){
						/* Get message size */
						byte[] len = new byte[4];
						is.read(len);
						int size = ToolKit.bigEndianBytesToInt(len, 0);
						
						if(size>0){//Size 0 is a keepalive
							Message m = new Message();
							/* Get message type */
							m.messageType = is.read();
							
							if(m.messageType>3){//1-3 dont have an additional payload
								/* Read the rest of the message */
								if(m.messageType==7){
									m.block=is.readInt();
									m.offset=is.readInt();
									m.message=new byte[size-9];
									is.readFully(m.message);
								}else if(m.messageType==6){
									m.block=is.readInt();
									m.offset=is.readInt();
									m.length=is.readInt();
								}else{
									m.message = new byte[size-1];
									is.readFully(m.message);
								}
							}
							synchronized(inQueue){
								/* Add message to the incoming queue, and notify anyone waiting on it */
								inQueue.add(m);
								inQueue.notify();
							}
							
						}
					}
				}catch(Exception e){}
			}
		}
		
		
		
		
		/**
		 * 
		 * @author jowen
		 * @author mvoswinkel
		 * Thread Sends KeepAlive messages to the other peer every 30 seconds
		 */
		class KeepAlive extends Thread{
			/*
			 * Actual method that adds messages to the Queue, notifies it, then sleeps.
			 */
			public void run(){
				while(downloading){
					/* Generate a 4 byte array of 0s which is a keep alive message */
					byte[] b = new byte[4];
					/* Add the message to the outgoing Queue and notify it */
					synchronized(outQueue){
						outQueue.add(b);
						outQueue.notify();
					}
					try{
						/* Wait for 30 seconds and repeat */
						sleep(30000);
					}catch(Exception e){}
				}
			}
		}
		
		
		
		
		/**
		 * 
		 * @author jowen
		 * @author mvoswinkel
		 * 
		 * Thread sends messages to peer.  It sleeps until it is notified that there is something in the Queue, sends it, and sleeps again.
		 * 
		 */
		class Sender extends Thread{
			/**
			 * Function sends messages to output stream when they are in the Queue
			 */
			public void run(){
				try{
					while(downloading){
						byte[] b;
						/*
						 * If there is nothing in the queue, block until there is something
						 * and am notified.  Otherwise remove it and set it to the byte[] b
						 */
						synchronized(outQueue){
							if(outQueue.isEmpty())outQueue.wait();
							b = outQueue.remove();
						}
						/* Write b to the output stream and flush, then repeat */
						os.write(b);
						os.flush();
					}
				}catch(Exception e){}
			}			
		}
	}
}