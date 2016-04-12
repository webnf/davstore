# davstore

A Clojure WebDAV server backed by a git-like blob store and a datomic
database to hold the directory structure.

This started as a demo app for namespaced xml in clojure, but grew pretty practical.

## Usage

To quickly start a server from a checkout

    git clone https://github.com/webnf/davstore.git
	cd davstore
    lein ring server-headless 8080

Then mount the webdav share with a client of choice:
- `mount -t davfs http://localhost:8080/files /mnt/dav`
- enter `dav://localhost:8080/files` into gnome's file manager
- OSX is untested yet
- Windows net mounts work, but only for reading

This creates an in-memory datomic database for the file system and
stores the file blobs in `/tmp/davstore-app`.
The default webdav root is `#uuid "7178245f-5e6c-30fb-8175-c265b9fe6cb8"`

For other use cases, please check out the code in `davstore.app` and FAQ.
To use it as a library, add it to your dependencies:

	[webnf/davstore "0.2.0-SNAPSHOT"]

and use it as a ring handler:

    (require '[davstore.app :refer [wrap-store file-handler]])
	(def handler (wrap-store file-handler
	               "/var/db/blobs"
	               "datomic:free://localhost:4334/your-store"
				   "/uri/prefix/"))

## How?

File blobs are stored in files named by the SHA-1 of their content, in a directory radix-tree of depth 1, similar to `.git/objects`. The difference is, that no file header is added before hashing and they are stored uncompressed.

Directory structures are stored in datomic.

## Why ...

### ... at all?
User managed content in web applications is a pain: One would like to have all changable content in the database, but most database engines are bad at storing large byte arrays. That's what the file system is for.

Incidentally users also want to have a file-system-like view via a net mount. Also, we developers want a net mount, so that our web app is not the only quick interface to user-accessible files.

But letting the user directly interact with the file system, e.g. via an FTP server, is also horrible:
- Your server wants to get notified when something changes
- Accidental modification should not be usual and easily detectable
- File names should not be subject to the idiosyncracies of the underlying file system
- Files with identical content should be deduplicated

### ... steal git's file structure for blobs?
The single-level git sha-1 radix tree is Mr. Torvalds' output of what must have been a comparatively long hammocking session over a simple question, that he is uniquely qualified to answer: What's the best tradeoff between collision-freeness, name-length, file-seek performance and input performance, with priority to file-seek performance.

### ... not actually be git-compatible?
This project's priority is to maximise serving performance from the blob storage. That rules out the transparent compression.

The file header is also omitted, in order to be able to respond with a plain `java.util.File` object (in contrast to a an nio channel). This should maximise the chance, that zero-copy serving (sendfile(2)) will be employed with any given java server.

### ... use datomic?
Because didn't you always want an undo slider on your web resources / network share? It just fits perfectly with content addressed file blobs.

### ... not support windows?
Windows requires level 2 DAV (mainly locks), which are not implemented yet.

## FAQ

#### Can I use it without datomic?
Not right now. Though you might be able to reuse the data.xml functions for webdav to write your own database binding.

Pull Request welcome.

#### Why is the default root `#uuid "7178245f-5e6c-30fb-8175-c265b9fe6cb8"`?
It's the version 5 (named by sha-1) uuid of the clojure symbol `davstore.app/root-id`:
```
(java.util.UUID/nameUUIDFromBytes
  (.digest (java.security.MessageDigest/getInstance "SHA-1")
           (.getBytes (str 'davstore.app/root-id) "UTF-8")))
```
This is also the symbol, that it's bound to in the API.

#### What's missing?
You tell me :-)

A few thoughts:
- Serialize and restore directory structure from / to blob storage.
- Make core.typed check it.
- Split it up more.
- Git import/export

## License

Copyright ↄ⃝ 2016 Herwig Hochleitner

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
