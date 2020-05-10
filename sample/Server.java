import java.io.*;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.registry.*;
import java.rmi.RemoteException;
import java.rmi.Naming;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;


public class Server extends UnicastRemoteObject implements RemoteFile {
	public String rootdir = "";    // root directory
	public static File root;       // root directory file object

	private static Map<String, Integer> numReaders = new ConcurrentHashMap<String, Integer>();
	private static Map<String, Integer> numWriters = new ConcurrentHashMap<String, Integer>();

	private static final long PROXYSERVERRTT = 2000;  // 0.5 seconds

	// hashmap for keeping lease records: <filename, lease
	// private Map<String, Lease> leaseMap = new ConcurrentHashMap<String, Lease>();
	

	// Each file has a ReentrantReadWriteLock which allows multiple readers or one wirter
	private static Map<String, ReentrantReadWriteLock> server_locks = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
	private static final Object server_map_locks = new Object();  // use to lock hashmap server_locks when inserting
	private static final int MaxLen = 409600;             // maximum chunking size

	private static final Long DEFAULTTERM = (long) 120000;	// 2 min 

	protected Server() throws RemoteException {
		super();
		// TODO Auto-generated constructor stub
	}

	/**
	 * Close a file and write back data updated. (No chunking)
	 * @param path
	 * @param writeBack: write back data
	 * @return latest version number, -1 if error
	 * @throws RemoteException
     */
	public long close_nochunking(String path, FileData writeBack) throws RemoteException {


		// System.out.println("Server enter CLOSE: " + path);
		path = rootdir + getOrigPath(path);
		try {
			// simulate RTT (network delay between server and proxy)
        	Thread.sleep(PROXYSERVERRTT);
			
			// write back
			FileOutputStream output = new FileOutputStream(path, false);
			output.write(writeBack.data);
			output.close();
			// release lock
			if (server_locks.get(path) != null) {
				server_locks.get(path).writeLock().unlock();
			}
			long ret = new File(path).lastModified();
			// System.out.println("Server enter CLOSE, returning: " + (int)ret);
			return ret;
		} catch (Exception e) {
			return -1;
		}
	}

	/**
	 * Close a file when receiving in chunking.
	 * Copy from the shallow copy to the master copy after all chunk received.
	 * @param tem_path: shallow copy path
	 * @param path: master copy path
	 * @return latest version number, -1 when error
	 * @throws RemoteException
     */
	public long close(String tem_path, String path) throws RemoteException {
		

		path = rootdir + getOrigPath(path);
		tem_path = rootdir + getOrigPath(tem_path);
		// System.out.println("enter CLOSE, path: " + path + ", tem_path: " + tem_path);
		
		try {
			// simulate RTT (network delay between server and proxy)
        	Thread.sleep(PROXYSERVERRTT);
			
			// write back the shallow copy to master copy
			copyFileUsingFileStreams(tem_path, path);
			Path tmp = Paths.get(tem_path);
			Files.delete(tmp);
			// release lock
			if (server_locks.get(path) != null) {
				server_locks.get(path).writeLock().unlock();
			}
			return new File(path).lastModified();
		} catch (Exception e) {
			return -1;
		} 
	}


	/**
	 * Unlink a file
	 * @param path
	 * @return Error message if error, null if success
	 * @throws RemoteException
     */
	public String unlink(String path) throws RemoteException {
		try{
			// simulate RTT (network delay between server and proxy)
	        Thread.sleep(PROXYSERVERRTT);

			// System.out.println("enter UNLINK, path: " + path );
			path = rootdir + path;
			File file = new File(path);

			// get write lock
			if (server_locks.get(path) != null) {
				server_locks.get(path).writeLock().lock();
			}

			// error handling
			if (!file.exists()) {
				if (server_locks.get(path) != null) {
					server_locks.get(path).writeLock().unlock();
				}
				return "ENOENT";
			}

			if (file.isDirectory()) {
				if (server_locks.get(path) != null) {
					server_locks.get(path).writeLock().unlock();
				} 
				return "EISDIR";
			}
		} catch (Exception e) {
			e.printStackTrace();
		} 
		
		// delete files
		try {
			Path tmp = Paths.get(path);
			Files.delete(tmp);
			return null;
		} catch (SecurityException e) {
			return "EPERM";
		} catch (NoSuchFileException x) {
			String errno = "ENOENT";
			if (x.getMessage().contains("Permission")) errno = "EACCESS";
			return errno;
		} catch (IOException e) {
			if (e.getMessage().contains("Bad file descriptor")) return "EBADF";
			else if (e.getMessage().contains("Permission")) return "EACCESS";
			return "EIO";
		} finally {
			if (server_locks.get(path) != null) {
				server_locks.get(path).writeLock().unlock();
				synchronized(server_map_locks) {
					server_locks.remove(path);
				}
			} 
		}
	}


	// calculateLeaseTerm
	public static long calculateLeaseTerm (String filename){
		long term;
		int nReaders = numReaders.getOrDefault(filename, 0);
		int nWriters = numWriters.getOrDefault(filename, 0);

		if (nReaders > 1.5 * nWriters) {
			term = (long) DEFAULTTERM * 2;
		} else if (nWriters > 1.5 * nReaders) {
			term = (long) DEFAULTTERM / 2;
		} else {
			term = DEFAULTTERM;
		}
		

		// System.out.println("enter calculateLeaseTerm, path: " + filename + ", term: " + (int) term);

		return term;
	}



	/**
	 * Open a file at path.
	 * Return file's metadata. If new version detected, return file data as well.
	 * @param path: file path
	 * @param option: open operation(1-create, 2-createnew, 3-read, 4-write)
	 * @param version: cache latest version
	 * @return FileData class contains file's metadata, null if not in rootdir
	 * @throws RemoteException
	 */
	public FileData open(String path, int option, long version) throws RemoteException {
		try {
			// simulate RTT (network delay between server and proxy)
	        Thread.sleep(PROXYSERVERRTT);
	    } catch(Exception e) {
	    	e.printStackTrace();
	    }


		String mode = null;
		String type = null;

		// if not in root directory
		path = rootdir + getOrigPath(path);

		File file = new File(path);
		if (!isSubDirectory(file)) { return null; }
		FileData file_data = new FileData(0, new byte[0]);

		if (option == 3) {
			mode = "r";
			type = "READ";
		} else {
			mode = "rw";
			type = "WRITE";
		}

		// System.out.println("enter OPEN, path: " + path  +  ", type: " + type );

		// if file exist
		if (file.exists()) {
			file_data.isExist = true;

			// for create_new return error
			if (option == 2) {
				file_data.isError = true;
				// if (server_locks.get(path) != null) {server_locks.get(path).readLock().unlock();}
				return file_data;
			}

			// if is directory
			if (file.isDirectory()) {
				// if (server_locks.get(path) != null) {server_locks.get(path).readLock().unlock();}
				file_data.isDir = true;
			} else {
				// if not directory, read data according to version
				if (option == 3) {
					numReaders.put(path, numReaders.getOrDefault(path, 0)+ 1);

					// get read lock
					if (server_locks.get(path) != null) {
						server_locks.get(path).readLock().lock();
					} else {
						synchronized(server_map_locks) {
							if (server_locks.get(path) == null) {server_locks.put(path, new ReentrantReadWriteLock());}
							server_locks.get(path).readLock().lock();
							// System.out.println("OPEN: PUT READ LOCK with path: " + path);
						}
					}
				}
				else {
					numWriters.put(path, numWriters.getOrDefault(path, 0)+ 1);

					// get write lock
					if (server_locks.get(path) != null) {
						server_locks.get(path).writeLock().lock();
					} else {
						synchronized(server_map_locks) {
							if (server_locks.get(path) == null) {server_locks.put(path, new ReentrantReadWriteLock());}
							server_locks.get(path).writeLock().lock();
							// System.out.println("OPEN: PUT WRITE LOCKwith path: " + path);
						}
					}
				}

				try {
					RandomAccessFile raf = new RandomAccessFile(path, mode);
					long server_version = file.lastModified();

					// if new version detected, return data as well
					if (server_version > version) {
						long size = file.length();
						byte[] data = new byte[MaxLen <= (int)size? MaxLen:(int)size];
						raf.read(data);
						raf.close();
						file_data.version = server_version;
						file_data.len = size;
						file_data.data = data;
					}
				} catch (FileNotFoundException e) {
					file_data.isError = true;
					file_data.ErrorMsg = e.getMessage();
				} catch (SecurityException e) {
					file_data.isError = true;
					file_data.ErrorMsg = e.getMessage();
				} catch (IOException e) {
					file_data.isError = true;
					file_data.ErrorMsg = e.getMessage();
				}
			}
		}

		// if file not exist
		else {
			file_data.isExist = false;

			// if not exist, return error for read and write
			if (option >= 3) {
				// if (server_locks.get(path) != null) {server_locks.get(path).readLock().unlock();}
				return file_data;
			}

			// if is directory
			if (file.isDirectory()) {
				// if (server_locks.get(path) != null) {server_locks.get(path).readLock().unlock();}
				file_data.isDir = true;
			} else {
				// create new file if it is not directory
				if (option <= 2) {
					try { file.createNewFile();
					} catch (IOException e) {
						file_data.isError = true;
						file_data.ErrorMsg = e.getMessage();
					}
					file_data.len = file.length();
					file_data.version = file.lastModified();
				}
			}
		}

		// create a lease
		long term = calculateLeaseTerm(path);
		System.out.println("server handed out lease for file: " + path);
		Lease newLease = new Lease(term, type, path);
		file_data.lease = newLease;
		// leaseMap.put(filename, newLease);
		return file_data;
	}

	// renew lease
	public static Lease renewLease(Lease old) throws RemoteException {
		try {
			// simulate RTT (network delay between server and proxy)
	        Thread.sleep(PROXYSERVERRTT);
	    } catch(Exception e) {
	    	e.printStackTrace();
	    }

		// System.out.print("renewLease with file: " + old.filename);

		// check if any other user is starved for the file??
		String filename = old.filename;

		// if blocking other clients, renewal is not approved
		ReentrantReadWriteLock rwlock = server_locks.get(filename);
		if (rwlock == null) {
			// System.out.print("ERRORR! renew null lock: " + filename);
			Lease renewed = new Lease(calculateLeaseTerm(filename), old.type, filename);
			return renewed;
		} else if (server_locks.get(filename).getQueueLength() > 0) {
			// System.out.print("HAS WAITING CLIENTS " + filename);
			return null;
		} else {
			// System.out.print("NO WAITING CLIENTS  " + filename);
			Lease renewed = new Lease(calculateLeaseTerm(filename), old.type, filename);
			return renewed;
		}
	}

	/**
	 * Read data from a file at offset, maximum reading size MaxLen
	 * Used for chunking read.
	 * @param path: file path
	 * @param offset: file pointer
	 * @return FileReadData class contains data read
	 * @throws RemoteException
     */
	@Override
	public FileReadData read(String path, long offset) throws RemoteException {


		path = rootdir + getOrigPath(path);

		// System.out.println("enter READ, path: " + path  +  ", offset: " + (int) offset );

		try {
			// simulate RTT (network delay between server and proxy)
        	Thread.sleep(PROXYSERVERRTT);

			// read data
			RandomAccessFile raf = new RandomAccessFile(path, "rw");
			raf.seek(offset);
			byte[] buf = new byte[MaxLen];
			int size = raf.read(buf, 0, MaxLen);
			offset = raf.getFilePointer();
			raf.close();
			if (size < MaxLen) return new FileReadData(offset, Arrays.copyOf(buf, size), size);
			return new FileReadData(offset, buf, size);
		} catch (Exception e) {
			e.printStackTrace(System.err);
		} 
		return null;
	}


	/**
	 * Write back data to a file at offset from buf of size bytes
	 * Used for chunking write back to shallow copy.
	 * @param path
	 * @param offset: file pointer
	 * @param buf data buffer
	 * @param size: buffer size
	 * @return next file pointer after write
	 * @throws RemoteException
     */
	@Override
	public long write(String path, long offset, byte[] buf, int size) throws RemoteException {

		path = rootdir + getOrigPath(path);

		// System.out.println("enter WRITE, path: " + path  +  ", offset: " + (int)offset + ", buf: " + new String(buf) );

		try {
			// simulate RTT (network delay between server and proxy)
        	Thread.sleep(PROXYSERVERRTT);

			RandomAccessFile raf = new RandomAccessFile(path, "rw");
			raf.seek(offset);
			raf.write(buf, 0, size);;
			offset = raf.getFilePointer();
			raf.close();
			return offset;
		} catch (Exception e) {
			e.printStackTrace(System.err);
			return -1;
		}
	}


	/**
	 * Copy file from path str1 to path str2
	 * @param str1: first file path
	 * @param str2: second file path
	 * @throws IOException
     */
	private static void copyFileUsingFileStreams(String str1, String str2)
			throws IOException {
		File source =new File(str1);
		File dest =new File(str2);
		if (!dest.exists()) dest.createNewFile();
		InputStream input = null;
		OutputStream output = null;
		try {
			input = new FileInputStream(source);
			output = new FileOutputStream(dest);
			byte[] buf = new byte[2046];
			int bytesRead;
			while ((bytesRead = input.read(buf)) > 0) {
				output.write(buf, 0, bytesRead);
			}
		} finally {
			input.close();
			output.close();
		}
	}


	/**
	 * Recursively check if a file belongs to root directory
	 * @param file object
	 * @return true if in the directory, false otherwise
     */
	private boolean isSubDirectory(File file) {
		File tmp;
		try {
			tmp = file.getCanonicalFile();
		} catch (IOException e) {return false;}

		while (tmp != null) {
			if (root.equals(tmp)) { return true; }
			tmp = tmp.getParentFile();
		}
		return false;
	}


	/**
	 * Map client side path with canonical path
	 * @param new_path: client side path
	 * @return: server side path
     */
	private static String getOrigPath(String new_path) {return new_path.replaceAll("%`%", "/");}
	
	
    public static void main(String args[]) {
        try {
        	 // Bind the remote object's stub in the registry
        	Server server = new Server();
        	LocateRegistry.createRegistry(Integer.parseInt(args[0]));
            Registry registry = LocateRegistry.getRegistry(Integer.parseInt(args[0]));
            registry.bind("RemoteFile", server);

			// root directory setup
            server.rootdir = args[1] + "/";
            server.root = new File(server.rootdir).getCanonicalFile();
            System.err.println("Server ready, rootdir:" + args[1]);
        } catch (Exception e) {
            System.err.println("Server exception: " + e.toString());
            e.printStackTrace();
        }
    }
}
