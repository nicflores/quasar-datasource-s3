# Quasar S3 Datasource

[![Build Status](https://travis-ci.org/slamdata/quasar-datasource-s3.svg?branch=master)](https://travis-ci.org/slamdata/quasar-datasource-s3)

## Usage

```sbt
libraryDependencies += "com.slamdata" %% "quasar-datasource-s3" % <version>
```

## Configuration

The configuration of the S3 datasource has the following JSON format

```
{
  "bucket": String,
  "jsonParsing": "array" | "lineDelimited",
  ["compressionScheme": "gzip",]
  ["credentials": Object]
}
```

* `bucket` the URL of the S3 bucket to use, e.g. `https://yourbucket.s3.amazonaws.com`
* `jsonParsing` the format of the resources that are assumed to be in the container. Currently array-wrapped 
  (`"array"`) and line-delimited (`"lineDelimited"`) are supported.
* `compressionScheme` (optional, default = empty) compression scheme that the resources in the container are assumed 
  to be compressed with. Currrently gzip (`"gzip"`) is supported.
  If omitted, the resources are not assumed to be compressed.
* `credentials` (optional, default = empty) S3 credentials to use for access in case the bucket is not public. 
  Object has the following format: `{ "accessKey": String, "secretKey": String, "region": String }`.
  The `credentials` section can be omitted completely for public buckets, but for private buckets the section needs 
  to be there with all 3 fields specified.

Example:

```
{
  "bucket": "https://yourbucket.s3.amazonaws.com",
  "jsonParsing": "array",
  "compressionScheme": "gzip",
  "credentials": {
    "accessKey": "some access key",
    "secretKey": "super secret key",
    "region": "us-east-1"
  }
}
```

### Running the test suite for secure buckets

You need to decode the base64-encoded credentials.

For GNU `base64`:

```
base64 -d testCredentials.json.b64 > testCredentials.json
```

For BSD (or macOS) `base64`:

```
base64 -D -i testCredentials.json.b64 -o testCredentials.json
```

After this, you should be able to run the `SecureS3DatasourceSpec` spec
