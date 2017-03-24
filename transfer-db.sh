#!/bin/bash -xeu

# Start MariaDB
docker run --name rems_mysql -p 3306:3306 --rm -e MYSQL_DATABASE=rems_mysql -e MYSQL_ALLOW_EMPTY_PASSWORD=yes -d mariadb

# Wait until the database has started
sleep 30

# Load dump
docker run -i --link rems_mysql:mysql --rm mariadb mysql -hmysql -uroot rems_mysql < $1

# Check contents of MariaDB
#docker run -it --link rems_mysql:mysql --rm mariadb mysql -hmysql -uroot --execute="USE rems_mysql; SELECT * from rms_catalogue_item;"

# Start mysql client
#docker run -it --link rems_mysql:mysql --rm mariadb mysql -hmysql -uroot

# Create target schema in Postgres
#docker run -it --rm postgres psql -h $PGHOST -U postgres -c 'DROP SCHEMA IF EXISTS transfer CASCADE; CREATE SCHEMA transfer;' rems;

# Load data from MariaDB into Postgres
docker run -it --rm --link rems_mysql:mysql dimitri/pgloader pgloader --set "search_path='transfer'" --verbose mysql://root@rems_mysql/rems_mysql postgresql://$PGUSER@$PGHOST/rems

docker run -i --rm postgres psql -h $PGHOST -U $PGUSER < resources/sql/transfer.sql

# Stop (and remove) MariaDB
docker stop rems_mysql