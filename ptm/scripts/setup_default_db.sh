#!/usr/bin/env bash
#
# List of the scripts that need to be executed
# to configure a (Postgres) database from scratch.
#

# Database parameters
# TODO: Change as needed.
dbhost=localhost
dbname=djangodb
dbadmin=django_admin

# Paths to various files
# TODO: Change these paths as needed
data_dir=/home/rayder441/sandbox/javanlp/projects/mt/ptm/data
script_dir=/home/rayder441/sandbox/javanlp/projects/mt/ptm/scripts

# Setup the default tables
echo Installing default tables with SQL script...
./run_sql_script.sh "$dbhost" "$dbname" "$dbadmin" "$script_dir"/sql/default_db.sql

# Load the country list
echo Installing the list of countries...
country_file="$data_dir"/country-list.csv
./csv_to_postgres.sh "$dbhost" "$dbname" "$dbadmin" tm_country < "$country_file"

echo Loading the documents...
# En documents
tgt_seq_pk=1
src_seq_pk=1
doc="$data_dir"/en/proc/training.txt
n_lines=`wc -l "$doc" | awk '{ print $1 }'`
echo Loading "$doc"
./load_source_doc.sh "$doc" "$src_seq_pk" 1
./load_target_doc.sh "$data_dir"/en/proc/trans/training.ar "$tgt_seq_pk" 2 "$src_seq_pk"
let tgt_seq_pk="$tgt_seq_pk + $n_lines"
./load_target_doc.sh "$data_dir"/en/proc/trans/training.fr "$tgt_seq_pk" 3 "$src_seq_pk"
let tgt_seq_pk="$tgt_seq_pk + $n_lines"
./load_target_doc.sh "$data_dir"/en/proc/trans/training.de "$tgt_seq_pk" 4 "$src_seq_pk"
let tgt_seq_pk="$tgt_seq_pk + $n_lines"

let src_seq_pk="$src_seq_pk + $n_lines"


doc="$data_dir"/en/proc/Sun_Wikipedia.txt
n_lines=`wc -l "$doc" | awk '{ print $1 }'`
echo Loading "$doc"
./load_source_doc.sh "$doc" "$src_seq_pk" 1
./load_target_doc.sh "$data_dir"/en/proc/trans/Sun_Wikipedia.ar "$tgt_seq_pk" 2 "$src_seq_pk"
let tgt_seq_pk="$tgt_seq_pk + $n_lines"
./load_target_doc.sh "$data_dir"/en/proc/trans/Sun_Wikipedia.fr "$tgt_seq_pk" 3 "$src_seq_pk"
let tgt_seq_pk="$tgt_seq_pk + $n_lines"
./load_target_doc.sh "$data_dir"/en/proc/trans/Sun_Wikipedia.de "$tgt_seq_pk" 4 "$src_seq_pk"
let tgt_seq_pk="$tgt_seq_pk + $n_lines"

let src_seq_pk="$src_seq_pk + $n_lines"

doc="$data_dir"/en/proc/Free_will_Wikipedia.txt
n_lines=`wc -l "$doc" | awk '{ print $1 }'`
echo Loading "$doc"
./load_source_doc.sh "$doc" "$src_seq_pk" 1
./load_target_doc.sh "$data_dir"/en/proc/trans/Free_will_Wikipedia.ar "$tgt_seq_pk" 2 "$src_seq_pk"
let tgt_seq_pk="$tgt_seq_pk + $n_lines"
./load_target_doc.sh "$data_dir"/en/proc/trans/Free_will_Wikipedia.fr "$tgt_seq_pk" 3 "$src_seq_pk"
let tgt_seq_pk="$tgt_seq_pk + $n_lines"
./load_target_doc.sh "$data_dir"/en/proc/trans/Free_will_Wikipedia.de "$tgt_seq_pk" 4 "$src_seq_pk"
let tgt_seq_pk="$tgt_seq_pk + $n_lines"

let src_seq_pk="$src_seq_pk + $n_lines"

doc="$data_dir"/en/proc/Tornado_Wikipedia.txt
n_lines=`wc -l "$doc" | awk '{ print $1 }'`
echo Loading "$doc"
./load_source_doc.sh "$doc" "$src_seq_pk" 1

let src_seq_pk="$src_seq_pk + $n_lines"

doc="$data_dir"/en/proc/Schizophrenia_Wikipedia.txt
n_lines=`wc -l "$doc" | awk '{ print $1 }'`
echo Loading "$doc"
./load_source_doc.sh "$doc" "$src_seq_pk" 1

let src_seq_pk="$src_seq_pk + $n_lines"

echo Done with default database setup!

