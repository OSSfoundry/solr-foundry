Apache Solr Content Extraction Library (Solr Cell)
==================================================

Introduction
------------

Apache Solr Extraction provides a means for extracting and indexing content contained in "rich" documents, such
as Microsoft Word, Adobe PDF, etc.  (Each name is a trademark of their respective owners)  This contrib module
uses Apache Tika to extract content and metadata from the files, which can then be indexed.  For more information,
see https://solr.apache.org/guide/uploading-data-with-solr-cell-using-apache-tika.html

Getting Started
---------------
You will need Solr up and running.  Then, simply add the extraction JAR file, plus the Tika dependencies (in the ./lib folder)
to your Solr Home lib directory.  See https://solr.apache.org/guide/uploading-data-with-solr-cell-using-apache-tika.html for more details on hooking it in
 and configuring.

