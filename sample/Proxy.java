/**
 * This is a File Proxy with LRU cache
 * File proxy uses open-close semantics and check-on-use protocol.
 *
 * LRU cache and fd generation is locked explicitly by lock object
 * to ensure atomic operation.
 *
 * Supports open, read, write, unlink and lseek operation.
 *
 */
import java.util.Timer;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class Proxy {
    public static String cacheDir;    // cache directory
    public static int cacheSize;      // cache size
    public static RemoteFile server;  // server RPC interface object
    public static ProxyCache cache;   // LRU cache object

    // hashmap: fd-randomAccessFile object pair for uniquely reading writing for each fd
    private static Map<Integer, RandomAccessFile> fd_map = new ConcurrentHashMap<Integer, RandomAccessFile>();
    // hashmap: fd-path pair for recording path
    private static Map<Integer, String> fd_path = new ConcurrentHashMap<Integer, String>();
    private static Integer fd = 6;   // fd

    private static Map<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private static final Object map_lock = new Object();  // use to lock hashmap locks when inserting
    private static Map<String, Lease> lease_map = new ConcurrentHashMap<String, Lease>();
    
    private static Map<String, Boolean> dirty_file = new ConcurrentHashMap<String, Boolean>();

    private final static Object fd_lock = new Object();     // used for lock fd generation
    private final static Object cache_lock = new Object();  // used for explicit lock cache
    // private static final int MAX_FILENUM = 10000000000;       // Maximum file that can open
    private static final int EACCESS = -13;       // errno
    private static final int EIO = -5;            // errno
    private static final int MaxLen = 409600;     // Maxlen for chunking

    public static long leaseterm;

    private static class FileHandler implements FileHandling {
        class leaseterm_checker extends TimerTask {
            private Lease lease;

            leaseterm_checker ( Lease lease ) {
                this.lease = lease;
            }

            public void run() {
                System.out.println("enter TimerTask");
                try {
                    Lease newlease = Server.renewLease(lease);

                    // if renewal is disapproved
                    if (lease == null) {
                        lease_map.remove(lease);
                        throw new RuntimeException("Lease renewal failed for file: " + lease.filename);
                    } else {
                        leaseterm = newlease.term;
                        lease_map.put(lease.filename, newlease);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * open: proxy open fuction
         * @param path:file path
         * @param OpenOption: open option
         * @return fd or errno
         */
        public int open(String orig_path, OpenOption o) {

            // Too many open files
            // if (fd_map.size() > MAX_FILENUM) { return Errors.EMFILE; }

            // check cache status and get current version
            // String path = mapPath(orig_path);

            // System.out.println("enter OPEN, path: " + path );

            // check if already has lease
            // check if already has lease, not need to get new lease
            if (lease_map.containsKey(orig_path) == false ) {
                long crt_version = getVersion(orig_path);
                boolean inCache = crt_version == -1 ? false : true;
                String path = cacheDir + orig_path;
                int crt_fd = 0;

                // if in cache, only get file's metadata, otherwise get data as well
                FileData new_file = getFileData(orig_path, crt_version, o);

                if (new_file == null) return Errors.ENOENT;

                // handle no such file and is_directory fault
                if (!new_file.exists() && (o == OpenOption.READ || o == OpenOption.WRITE)) {
                    return Errors.ENOENT;
                } else if (new_file.isDir && o!= OpenOption.READ && o!= OpenOption.CREATE_NEW){
                    return Errors.EISDIR;
                }

                // System.out.println("OPEN, lease_map NO: " + orig_path );
                Lease lease = new_file.lease;

                lease_map.put(orig_path, lease);
                leaseterm = lease.term;

                // create a thread to check if lease is going to expire
                Timer timer = new Timer();
                timer.schedule(new leaseterm_checker(lease), 0, leaseterm);

                // do the open operation
                switch (o) {
                    case CREATE:

                        // System.out.println("enter OPEN_CREATE, path: " + orig_path );

                        if (new_file.isError) return handleError(new_file.ErrorMsg);
                        crt_fd = getFd();

                        // get read lock
                        if (locks.get(orig_path) != null) {
                            locks.get(orig_path).writeLock().lock();
                        } else {
                            synchronized(map_lock) {
                                if (locks.get(orig_path) == null) {locks.put(orig_path, new ReentrantReadWriteLock());}
                                locks.get(orig_path).writeLock().lock();
                            }
                        }
                        System.out.println("open_CREATE, added WRITE lock with path: " + orig_path);


                        return open_Create_file(crt_fd, path, new_file, crt_version);

                    case CREATE_NEW:

                        // System.out.println("enter OPEN_CREATENEW, path: " + orig_path );

                        // error handling
                        if (new_file.exists()) { return Errors.EEXIST; }
                        if (new_file.isDirectory()) { return Errors.EISDIR;}
                        if (new_file.isError) return handleError(new_file.ErrorMsg);
                        crt_fd = getFd();

                        // get read lock
                        if (locks.get(orig_path) != null) {
                            locks.get(orig_path).writeLock().lock();
                        } else {
                            synchronized(map_lock) {
                                if (locks.get(orig_path) == null) {locks.put(orig_path, new ReentrantReadWriteLock());}
                                locks.get(orig_path).writeLock().lock();
                            }
                        }
                        System.out.println("open_CREATE_NEW, added WRITE lock with path: " + orig_path);

                        return open_CreateNew_file(crt_fd, path, new_file,crt_version);

                    case READ:
                        // System.out.println("enter OPEN_READ, path: " + orig_path );

                        if (new_file.isError) return handleError(new_file.ErrorMsg);
                        // if is a directory
                        if (new_file.isDirectory()) {
                            crt_fd = getFd();
                            fd_path.put(crt_fd, path);
                            return crt_fd;
                        }
                        // if is a file
                        crt_fd = getFd();

                        // get read lock
                        if (locks.get(orig_path) != null) {
                            locks.get(orig_path).readLock().lock();
                        } else {
                            synchronized(map_lock) {
                                if (locks.get(orig_path) == null) {locks.put(orig_path, new ReentrantReadWriteLock());}
                                locks.get(orig_path).readLock().lock();
                            }
                        }
                        System.out.println("open_READ_file, added READ lock with path: " + orig_path);


                        return open_Read_file(crt_fd, path, new_file, crt_version);

                    case WRITE:
                        // System.out.println("enter OPEN_WRITE, path: " + path );
                        if (new_file.isError) return handleError(new_file.ErrorMsg);
                        crt_fd = getFd();

                        // get read lock
                        if (locks.get(orig_path) != null) {
                            locks.get(orig_path).writeLock().lock();
                        } else {
                            synchronized(map_lock) {
                                if (locks.get(orig_path) == null) {locks.put(orig_path, new ReentrantReadWriteLock());}
                                locks.get(orig_path).writeLock().lock();
                            }
                        }
                        System.out.println("open_Write_file, added WRITE lock with path: " + orig_path);


                        return open_Write_file(crt_fd, path, new_file, crt_version);

                    default:
                        return Errors.EINVAL;
                }


            } else {
                System.out.println("found lease");
                Lease lease = lease_map.get(orig_path);

                long crt_version = -1;
                while (crt_version == -1) {
                    crt_version = getVersion(orig_path);
                }
                System.out.println("found lease, getVersion: " + crt_version);
                String path = cacheDir + orig_path;
                int crt_fd = 0;

                switch (o) {
                    case CREATE:
                        crt_fd = getFd();

                        // get write lock
                        if (locks.get(orig_path) != null) {
                            locks.get(orig_path).writeLock().lock();
                        } else {
                            synchronized(map_lock) {
                                if (locks.get(orig_path) == null) {locks.put(orig_path, new ReentrantReadWriteLock());}
                                locks.get(orig_path).writeLock().lock();
                            }
                        }
                        System.out.println("AVAIL CREATE: " + orig_path);
                        return open_Create_write_file_with_available_lease(crt_fd, path, crt_version);

                    case READ:
                        // System.out.println("enter OPEN_READ, path: " + orig_path );
                        // if is a file
                        crt_fd = getFd();

                        // get read lock
                        if (locks.get(orig_path) != null) {
                            locks.get(orig_path).readLock().lock();
                        } else {
                            synchronized(map_lock) {
                                if (locks.get(orig_path) == null) {locks.put(orig_path, new ReentrantReadWriteLock());}
                                locks.get(orig_path).readLock().lock();
                            }
                        }
                        System.out.println("AVAIL READ: " + orig_path);


                        return open_Read_file_with_available_lease(crt_fd, path , crt_version);

                    case WRITE:
                        // System.out.println("enter OPEN_WRITE, path: " + path );
                        crt_fd = getFd();

                        // get read lock
                        if (locks.get(orig_path) != null) {
                            locks.get(orig_path).writeLock().lock();
                        } else {
                            synchronized(map_lock) {
                                if (locks.get(orig_path) == null) {locks.put(orig_path, new ReentrantReadWriteLock());}
                                locks.get(orig_path).writeLock().lock();
                            }
                        }
                        System.out.println("AVAIL WRITE: " + orig_path);

                        return open_Create_write_file_with_available_lease(crt_fd, path, crt_version);

                    default:
                        return Errors.EINVAL;
                }
            }
        }

        /**
         * Write operation
         * @param fd
         * @param buf: write buffer
         * @return bytes write or errno
         */
        public long write(int fd, byte[] buf) {
            // error handling
            if (!fd_path.containsKey(fd)) { return Errors.EBADF;}
            File file = new File(fd_path.get(fd));

            // System.out.println("enter WRITE, path: " + fd_path.get(fd) );

            if (!file.exists()) { return Errors.ENOENT; }
            if (file.isDirectory()) { return Errors.EISDIR;}

            // perform write
            RandomAccessFile raf = fd_map.get(fd);
            try {
                dirty_file.put(file.getPath(), true);

                raf.write(buf);
                String name = fd_path.get(fd);
                long len = new File(name).length();
                // change length in cache
                synchronized (cache_lock) {
                    cache.set(name, (int) len);
                }
            } catch (IOException e) {
                // System.err.println(e.getMessage());
                if (e.getMessage().contains("Bad file descriptor")) return Errors.EBADF;
                else if (e.getMessage().contains("Permission")) return -13;
                else if (e.getMessage().contains("directory")) return Errors.EISDIR;
                return -5;
            }
            return buf.length;
        }


        /**
         * Perform read in proxy.
         * @param fd
         * @param buf
         * @return bytes read or errno
         */
        public long read(int fd, byte[] buf) {
            // error handling
            if (!fd_path.containsKey(fd)) { return Errors.EBADF; }
            File file = new File(fd_path.get(fd));

            // System.out.println("enter READ, path: " + fd_path.get(fd) );



            if (!file.exists()) { return Errors.ENOENT;}
            if (file.isDirectory()) { return Errors.EISDIR;}

            RandomAccessFile raf = fd_map.get(fd);
            try {
                int read_num = raf.read(buf);
                if (read_num == -1) return 0;
                synchronized (cache_lock) {
                    cache.get(fd_path.get(fd));
                }
                return (long) read_num;
            } catch (IOException e) {
                e.printStackTrace(System.err);
                if (e.getMessage().contains("Bad file")) return Errors.EBADF;
                else if (e.getMessage().contains("Permission")) return EACCESS;
                else if (e.getMessage().contains("directory")) return Errors.EISDIR;
                return -5;
            }
        }


        /**
         * Lseek operatino in cache
         * @param fd
         * @param pos: file operator
         * @param o option
         * @return file operator or errno
         */
        public long lseek(int fd, long pos, LseekOption o) {
            // error handling
            if (!fd_path.containsKey(fd)) return (long)Errors.EBADF;
            String path = fd_path.get(fd);

            // System.out.println("enter LSEEK, path: " + path  +  ", offset: " + (int) pos );


            File file = new File(path);
            if (!file.exists()) { return Errors.ENOENT;}
            if (file.isDirectory()) { return Errors.EISDIR; }

            // get pos
            RandomAccessFile raf = fd_map.get(fd);
            if (pos < 0) return Errors.EINVAL;
            switch (o) {
                case FROM_CURRENT:
                    try {
                        pos = raf.getFilePointer() + pos;
                    } catch (IOException e2) { return EIO; }
                    break;
                case FROM_END:
                    try {
                        pos = raf.length() + pos;
                    } catch (IOException e1) { return EIO; }
                    break;
                case FROM_START:
                    break;
                default:
                    return Errors.EINVAL;
            }
            if (pos < 0) { return Errors.EINVAL; }

            // perform lseek
            try {
                raf.seek(pos);
                synchronized (cache_lock) {
                    cache.get(fd_path.get(fd));
                }
                return pos;
            } catch (IOException e) {return EIO;}
        }


        /**
         * Unlink a file
         * @param path: file path
         * @return 0 fior success, errno for error
         */
        public int unlink(String path) {
            // System.out.println("enter UNLINK, path: " + path );
        
            try {
                String state = server.unlink(path);
                if (state == null) return 0;
                else if (state.equals("EACCESS")) return EACCESS;
                else if (state.equals("EIO")) return EIO;
                else if (state.equals("ENOENT")) return Errors.ENOENT;
                else if (state.equals("EPERM")) return Errors.EPERM;
                else if (state.equals("EBADF")) return Errors.EBADF;
                else return EIO;
            } catch (RemoteException e) {
                return EIO;
            }
        }


        /**
         * Close a file in proxy.
         * If read only, decrease reference count in cache.
         * If write happens, write back data to server.
         * @param fd
         * @return 0 for success, errno if error happens
         */
        public int close(int fd) {
        

            // Error handling
            if (!fd_path.containsKey(fd)) { return Errors.EBADF; }
            String path = fd_path.get(fd);
            File file = new File(path);
            if (!file.exists()) { return Errors.ENOENT; }

            // if directory
            if (file.isDirectory()) {
                fd_path.remove(fd);
                    // release lock
                if (locks.get(path) != null) {
                    locks.get(path).writeLock().unlock();
                }
                
                return 0;
            }

            if (dirty_file.containsKey(path)) {
                System.out.println("Dirty file");

                dirty_file.remove(path);

                // write back new version if it is not read-only
                // return lease and set occupied to false
                // if (!isReadOnly(path)) {
                path = path.substring(cacheDir.length());
                
                // int index = path.lastIndexOf("_w", path.lastIndexOf("_w") - 1);
                int index = path.lastIndexOf("_");
                // if (index < 0) return EIO;
                // System.out.println("enter CLOSE: path: " + path + ", index: " + index + ", one more: " + path.substring(0, index));
                try {

                    // write back data using RPC if no chunking
                    int len = (int) file.length();
                    long version = 0;
                    if (len <= MaxLen) {
                        path = path.substring(0, index);
                        byte[] data = new byte[len];
                        RandomAccessFile f = new RandomAccessFile(fd_path.get(fd), "r");
                        f.readFully(data, 0, len);
                        f.close();
                        FileData writeBack = new FileData(len, data);
                        version = server.close_nochunking(path, writeBack);
                        fd_map.get(fd).close();
                    }

                    // write back using chunking
                    else {
                        int write = 0;
                        long offset = 0;
                        RandomAccessFile f = new RandomAccessFile(fd_path.get(fd), "r");
                        byte[] buf = new byte[MaxLen];
                        while (write < len) {
                            int read_num = f.read(buf);
                            offset = server.write(path, offset, buf, read_num);
                            if (offset == -1) return EIO;
                            write += read_num;
                        }
                        f.close();
                        version = server.close(path, path.substring(0, index));
                        path = path.substring(0, index);
                    }

                    
                    // release lock
                    if (locks.get(path) != null) {
                        locks.get(path).writeLock().unlock();
                    }
                    

                    // rename it to read version
                    // file.renameTo(new File(cacheDir + path + "_r" + version));
                    file.renameTo(new File(cacheDir + path + "_" + version));
                    synchronized (cache_lock) {
                        // cache.setNewName(fd_path.get(fd), cacheDir + path + "_r" + version);
                        cache.setNewName(fd_path.get(fd), cacheDir + path + "_" + version);
                    }

                } catch (Exception e) {}
            }
            // }


            else {
                // if read-only data, decrease reference in cache
                try {
                    fd_map.get(fd).close();
                    // release lock
                    if (locks.get(path) != null) {
                        locks.get(path).readLock().unlock();
                    }
                    synchronized (cache_lock) {
                        cache.decreaseReference(fd_path.get(fd), 1);
                    }
                } catch (IOException e) {return EIO;}
            }
            System.err.println(cache.toString());
            fd_map.remove(fd);
            fd_path.remove(fd);
            // leaseMap.remove(path);
            return 0;
        }

        public void clientdone() {
        }


        /**
         * Open a file with WRITE operation.
         * @param crt_fd: fd
         * @param path: file path
         * @param new_file: File's metadata
         * @param crt_version: crt version in cache
         * @return fd or errno
         */
        private int open_Write_file(int crt_fd, String path, FileData new_file, long crt_version) {
            try {
                // make cache copy for this fd, if not in cache
                if (crt_version == -1 || new_file.version != -1) {
                    String orig_path = path;
                    // path = path + "_w" + crt_fd + "_w" + new_file.version;
                    path = path + "_" + new_file.version;
                    int state = 0;
                    synchronized (cache_lock) {
                        state = cache.set(path, (int) new_file.len, 1);
                    }
                    if (state == -1) return Errors.EMFILE;
                    RandomAccessFile tmp = new RandomAccessFile(path, "rw");
                    state = readFile(tmp, new_file, orig_path);
                    if (state != 0) return state;
                }

                // // make a copy of cached file
                // else {
                //     String orig_path = path;
                //     path = path + "_w" + crt_fd + "_w" + crt_version;
                //     copyFileUsingFileStreams(orig_path + "_r" + crt_version, path);
                //     synchronized (cache_lock) {
                //         cache.set(path, (int) new File(path).length(), 1);
                //     }

                // }

                RandomAccessFile raf = new RandomAccessFile(path, "rw");
                fd_map.put(crt_fd, raf);
                fd_path.put(crt_fd, path);
                return crt_fd;
            } catch (FileNotFoundException e) {
                if (e.getMessage().contains("Permission")) return Errors.EPERM;
                return Errors.ENOENT;
            } catch (SecurityException e) {
                return Errors.EPERM;
            }
            // } catch (IOException e) {
            //     return EIO;
            // }
        }

        private int open_Read_file_with_available_lease(int crt_fd, String path,long crt_version  ) {
            try{

                path = path + "_" + crt_version;
                synchronized (cache_lock) {
                    cache.addReference(path, 1);
                }

                RandomAccessFile raf = new RandomAccessFile(path, "r");
                fd_map.put(crt_fd, raf);
                fd_path.put(crt_fd, path);
            } catch (Exception e) {
                e.printStackTrace();
            }

            return crt_fd;

        }

        /**
         * Open a file with READ operation.
         * @param crt_fd: fd
         * @param path: file path
         * @param new_file: File's metadata
         * @param crt_version: crt version in cache
         * @return fd or errno
         */
        private int open_Read_file(int crt_fd, String path, FileData new_file, long crt_version) {
            try {
                // make cache copy if not in cache
                if (crt_version == -1 || new_file.version != -1) {
                    String orig_path = path;
                    // path = path + "_r" + new_file.version;
                    path = path + "_" + new_file.version;

                    RandomAccessFile tmp = new RandomAccessFile(path, "rw");
                    int state = readFile(tmp, new_file, orig_path);
                    if (state != 0) return state;
                    state = 0;
                    synchronized (cache_lock) {
                        cache.deleteOldVersion(path);
                        state = cache.set(path, (int) new_file.len, 1);
                    }
                    if (state == -1) return Errors.EMFILE;
                }
                // get a cache file
                else {
                    // path = path + "_r" + crt_version;
                    synchronized (cache_lock) {
                        cache.addReference(path, 1);
                    }
                }

                RandomAccessFile raf = new RandomAccessFile(path, "r");
                fd_map.put(crt_fd, raf);
                fd_path.put(crt_fd, path);


                return crt_fd;
            } catch (FileNotFoundException e) {
                if (e.getMessage().contains("Permission")) return Errors.EPERM;
                return Errors.ENOENT;
            } catch (SecurityException e) {
                return Errors.EPERM;
            }
        }

        /**
         * Open a file with CREATE NEW operation.
         * @param crt_fd: fd
         * @param path: file path
         * @param new_file: File's metadata
         * @param crt_version: crt version in cache
         * @return fd or errno
         */
        private int open_CreateNew_file(int crt_fd, String path, FileData new_file, long crt_version) {
            try {
                // path = path + "_w" + crt_fd + "_w" + new_file.version;
                path = path + "_" + new_file.version;
                RandomAccessFile raf = new RandomAccessFile(path, "rw");
                synchronized (cache_lock) {
                    cache.set(path, 0, 1);
                }
                fd_map.put(crt_fd, raf);
                fd_path.put(crt_fd, path);

                return crt_fd;
            } catch (FileNotFoundException e) {
                if (e.getMessage().contains("Permission")) return Errors.EPERM;
                return Errors.ENOENT;
            } catch (SecurityException e) {
                return Errors.EPERM;
            }
        }

        private int open_Create_write_file_with_available_lease(int crt_fd, String path, long crt_version) {
            try{
                // put it in map
                RandomAccessFile raf = new RandomAccessFile(path, "rw");
                fd_map.put(crt_fd, raf);
                fd_path.put(crt_fd, path);
            } catch (Exception e) {
                e.printStackTrace();
            }

            return crt_fd;
        }

        /**
         * Open a file with CREATE operation.
         * @param crt_fd: fd
         * @param path: file path
         * @param new_file: File's metadata
         * @param crt_version: crt version in cache
         * @return fd or errno
         */
        private int open_Create_file(int crt_fd, String path, FileData new_file, long crt_version) {
            try {
                // make cache copy for this fd if not in cache
                // if (crt_version == -1 || new_file.version != -1) {
                //     String orig_path = path;
                //     // path = path + "_w" + crt_fd + "_w" + new_file.version;
                //     path = path + "_" + new_file.version;
                //     int state = 0;
                //     synchronized (cache_lock) {
                //         state = cache.set(path, (int) new_file.len, 1);
                //     }
                //     if (state == -1) return Errors.EMFILE;
                //     RandomAccessFile tmp = new RandomAccessFile(path, "rw");
                //     state = readFile(tmp, new_file, orig_path);
                //     if (state != 0) return state;
                // } 

                // else {
                //     // if in cache, make a new copy for write
                //     String cache_path = path + "_r" + crt_version;
                //     path = path + "_w" + crt_fd + "_w" + crt_version;
                //     int state = 0;
                //     synchronized (cache_lock) {
                //         state = cache.set(path, (int) new File(cache_path).length(), 1);
                //     }
                //     if (state == -1) return Errors.EMFILE;
                //     copyFileUsingFileStreams(cache_path, path);
                // }
                
                path = path + "_" + crt_version;
                int state = 0;
                synchronized (cache_lock) {
                    state = cache.set(path, (int) new_file.len, 1);
                }
                if (state == -1) return Errors.EMFILE;
                RandomAccessFile tmp = new RandomAccessFile(path, "rw");
                state = readFile(tmp, new_file, path);
                if (state != 0) return state;

                // put it in map
                RandomAccessFile raf = new RandomAccessFile(path, "rw");
                fd_map.put(crt_fd, raf);
                fd_path.put(crt_fd, path);

                return crt_fd;
            } catch (FileNotFoundException e) {
                if (e.getMessage().contains("Permission")) return Errors.EPERM;
                return Errors.ENOENT;
            } catch (SecurityException e) {
                return Errors.EPERM;
            } 
            // catch (IOException e) {
            //     return EIO;
            // }
        }

        /**
         * Read a file from server in chunks
         * @param tmp: used for write to local copy
         * @param new_file: file data from server
         * @param orig_path: file's path
         * @return 0 on success, other for errors
         */
        private static int readFile(RandomAccessFile tmp, FileData new_file, String orig_path) {
            try {
                long total_len = new_file.len;
                long len = new_file.data.length;
                tmp.write(new_file.data);
                new_file.flush();

                FileReadData data;
                long offset = len;
                orig_path = orig_path.substring(cacheDir.length());
                while (len < total_len) {
                    data = server.read(orig_path, offset);
                    if (data == null) return EIO;
                    tmp.write(data.data);
                    len += data.size;
                    offset = data.offset;
                }
                tmp.close();
            } catch (IOException e) {
                return EIO;
            }
            new_file.flush();
            return 0;
        }


        // /** Copy a file from source to dest
        //  * Used for when making a copy for write
        //  * @param str1 source file
        //  * @param str2 destination file
        //  * @throws IOException
        //  */
        // private static void copyFileUsingFileStreams(String str1, String str2)
        //         throws IOException {
        //     File source = new File(str1);
        //     File dest = new File(str2);
        //     if (!dest.exists()) dest.createNewFile();
        //     InputStream input = null;
        //     OutputStream output = null;
        //     try {
        //         input = new FileInputStream(source);
        //         output = new FileOutputStream(dest);
        //         byte[] buf = new byte[2046];
        //         int bytesRead;
        //         while ((bytesRead = input.read(buf)) > 0) {
        //             output.write(buf, 0, bytesRead);
        //         }
        //     } finally {
        //         input.close();
        //         output.close();
        //     }
        // }

        /**
         * Use for handle error
         * @param errorMsg
         * @return
         */
        private static int handleError(String errorMsg) {
            if (errorMsg.contains("Permission")) return Errors.EPERM;
            if (errorMsg.contains("Bad file descriptor")) return Errors.EBADF;
            if (errorMsg.contains("No such")) return Errors.ENOENT;
            return Errors.EBADF;
        }

        // /**
        //  * Map absolute path to client-side path
        //  * @param orig_path
        //  * @return: client side path
        //  */
        // private static String mapPath(String orig_path) {
        //     return orig_path.replaceAll("/", "%`%");
        // }

        /**
         * Get current version of file in cache.
         * @param path: file path
         * @return -1 if not in cache, last-modified-timestamp if in cache
         */
        private long getVersion(String path) {
            long crt_version = -1;
            synchronized (cache_lock) {
                crt_version = cache.checkVersion(cacheDir + path);
                System.out.println("GETTING VER FROM CACHE: " + crt_version) ;
            }
            return crt_version;
        }


        /**
         * Get a new file descriptor.
         * This method is synchronized by fd_lock object.
         * @return new fd.
         */
        private int getFd() {
            int crt_fd = 0;
            synchronized (fd_lock) {
                crt_fd = fd++;
            }
            return crt_fd;
        }


        /**
         * Get a file metadata.
         * @param path file path
         * @param crt_version cuurent version in cache
         * @param Operation for open
         * @return FileData class contains file metadata
         */
        private FileData getFileData(String path, long crt_version, OpenOption o) {
            FileData new_file = null;
            try {
                switch (o) {
                    case CREATE:
                        new_file = server.open(path, 1, crt_version);
                        break;
                    case CREATE_NEW:
                        new_file = server.open(path, 2, crt_version);
                        break;
                    case READ:
                        new_file = server.open(path, 3, crt_version);
                        break;
                    case WRITE:
                        new_file = server.open(path, 4, crt_version);
                        break;
                }
                return new_file;
            } catch (RemoteException e1) {
                e1.printStackTrace(System.err);
                return null;
            }
        }
    }

    private static class FileHandlingFactory implements FileHandlingMaking {
        public FileHandling newclient() {
            return new FileHandler();
        }
    }


    public static void main(String[] args) throws IOException {
        cacheDir = args[2] + "/";                // cache dir
        cacheSize = Integer.parseInt(args[3]);   // cache size
        cache = new ProxyCache(cacheSize);       // set up new cache

        // bind a RMI service
        try {
            server = (RemoteFile) Naming.lookup("//" + args[0] +
                    ":" + args[1] + "/RemoteFile");         //objectname in registry 
            System.err.println("Proxy ready");
        } catch (Exception e) {
            System.err.println("Client exception: " + e.toString());
            e.printStackTrace();
        }

        System.err.println("Proxy start to work!");
        (new RPCreceiver(new FileHandlingFactory())).run();
    }
}

