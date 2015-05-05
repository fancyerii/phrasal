#!/usr/local/bin/doit -f
#
# (The above location is the standard if pydoit is installed
#  via 'pip install doit'.)
#
# A pydoit script for running the full Phrasal
# pipeline. This script replaces the old phrasal.sh.
#
# This script executes all underlying commands in a
# platform-independent manner.
#
# NOTE: This script is written for Python 2.7. Py3k support
#       is untested.
#
# Dependencies:
#    pip install pyaml
#    pip install doit
#
# Author: Spence Green
#
import doit
from doit import get_var
from datetime import datetime
import sys
import yaml
import os
import os.path
from os.path import basename
import shutil
import subprocess
import conf_keys as k
import re

#
# Doit configuration
#
DOIT_CONFIG = {
    # 0 := dont' print anything
    # 1 := print stderr only
    # 2 := print stderr and stdout
    'verbosity': 2,

    # Use multi-processing / parallel execution of tasks
    # Better to let Phrasal pipeline run sequentially so that
    # each task can use all cores.
    'num_process': 1
}

# Get conf file from command-line arguments
ARGS = {"conf": get_var('conf', None)}
err = lambda x : sys.stderr.write(x + os.linesep)
if not ARGS['conf']:
    err('Usage: %s conf=<file>' % (basename(sys.argv[2])))
    sys.exit(-1)

conf_file = ARGS['conf']
if not os.path.exists(conf_file):
    # Relative path
    conf_file = os.path.join(doit.get_initial_workdir(), conf_file)
    if not os.path.exists(conf_file):
        err('Configuration file not found: ' + conf_file)
        sys.exit(-1)

# Conf file format is YAML. Parse it.
with open(conf_file) as fd:
    CONFIG = yaml.load(fd)

# Experiment naming
DATE = datetime.now().strftime('%a_%b_%d_%Y_%H_%M_%S')
EXPERIMENT_NAME = CONFIG[k.EXPERIMENT].get(k.EXPERIMENT_NAME, DATE) if k.EXPERIMENT in CONFIG else DATE

# Constants for the files and folders generated by this script
# during execution
SYSTEM_DIR_LOC = 'system-dir'
SYSTEM_DIR = CONFIG[k.SYSTEM_DIR]
p = lambda x : os.path.join(SYSTEM_DIR, x)
CHECKPOINT_DIR = p('checkpoints')
LOGS_DIR = p('logs')
COPY_DATA_LOC = 'copy-data'
COPY_DATA_DIR = p(COPY_DATA_LOC)
DECODER_INI = p('%s.%s' % (EXPERIMENT_NAME, 'decoder.ini'))
DECODER_TUNE_INI = p('%s.%s' % (EXPERIMENT_NAME, 'decoder.tune.ini'))


# Checkpoint files
p = lambda x : os.path.join(CHECKPOINT_DIR, '%s.%s' % (x, EXPERIMENT_NAME))
CHECKPOINT_SYSTEM_DIR = p(k.SYSTEM_DIR)
CHECKPOINT_COPY_DATA = p(k.TASK_COPY_DATA)
CHECKPOINT_TUNE = p(k.TASK_TUNE)
CHECKPOINT_BUILD = p(k.TASK_BUILD)
CHECKPOINT_EVAL = p(k.TASK_EVAL)
CHECKPOINT_LEARN_CURVE = p(k.TASK_LEARN_CURVE)

def make_abs(path):
    """
    Relative path to absolute SYSTEM_DIR path.
    """
    return os.path.join(SYSTEM_DIR, basename(path))

def qualify_path(path):
    """
    Expand short-hand names in the CONFIG file.
    """
    if path.startswith(COPY_DATA_LOC):
        return os.path.join(SYSTEM_DIR, path)
    elif path.startswith(k.SYSTEM_DIR):
        path = re.sub('%s/' % (re.escape(SYSTEM_DIR_LOC)), '', path)
        return os.path.join(SYSTEM_DIR, path)
    elif path.startswith('/'):
        return path
    else:
        return make_abs(path)

# Global constants from the conf file. These are targets
# for the tasks below.
LM_FILE = qualify_path(CONFIG[k.TASK_LM][k.LM_OUTPUT])
TM_FILE = qualify_path(CONFIG[k.TASK_TM][k.TM_OUTPUT])
TUNE_WTS = make_abs('%s.online.final.binwts' % (EXPERIMENT_NAME))

# KenLM location in the Phrasal git repo
(PHRASAL_DIR, _) = os.path.split(sys.path[0])
KENLM_LIB = os.path.join(PHRASAL_DIR, 'src-cc')
KENLM_BIN = os.path.join(PHRASAL_DIR, 'src-cc', 'kenlm', 'bin')

def checkpoint(path, msg):
    """
    Make a checkpoint file on the local filesystem
    """
    with open(path, 'w') as outfile:
        outfile.write(msg + os.linesep)

def get_log_file_path(name):
    """
    Standardizes log file naming.
    """
    return os.path.join(LOGS_DIR, '%s.%s.log' % (EXPERIMENT_NAME, name))
        
def execute_shell_cmd(cmd, stdin=None, stdout=subprocess.PIPE,
                      stderr=subprocess.STDOUT):
    """
    Executes a command as a sub-process. Requires an underlying
    shell from the OS, but this should work on most platforms.

    Returns:
      The process handle.
    """
    print 'Executing:', cmd
    return subprocess.Popen(cmd, shell=True,
                            cwd=SYSTEM_DIR, env=os.environ,
                            universal_newlines=True,
                            stdin=stdin,
                            stdout=stdout,
                            stderr=stderr)

def get_java_cmd(class_str):
    """
    Get the user-specified JVM options
    """
    jvm_options = None
    if k.TASK_RUNTIME in CONFIG and k.RUNTIME_JVM in CONFIG[k.TASK_RUNTIME]:
        jvm_options = ' '.join(CONFIG[k.TASK_RUNTIME][k.RUNTIME_JVM])
    else:
        # Best GC settings for Phrasal as of JVM 1.8
        jvm_options = '-server -ea -Xmx5g -Xms5g -XX:+UseParallelGC -XX:+UseParallelOldGC -Djava.library.path=%s' % (KENLM_LIB)
    return 'java %s %s' % (jvm_options, class_str)

def task_mksystemdir():
    """
    Create the system directory and necessary sub-directories.
    """
    def make_dirs():
        if not os.path.exists(SYSTEM_DIR):
            os.makedirs(SYSTEM_DIR)
        if not os.path.exists(CHECKPOINT_DIR):
            os.makedirs(CHECKPOINT_DIR)
        if not os.path.exists(LOGS_DIR):
            os.makedirs(LOGS_DIR)
            
    return { 'actions' : [make_dirs],
             'targets' : [CHECKPOINT_SYSTEM_DIR]
         }
        
def task_build():
    """
    Build the Phrasal (git) repository.
    """
    def build_git_repo():
        if not k.TASK_BUILD in CONFIG:
            checkpoint(CHECKPOINT_BUILD, 'done')
            return
        d = CONFIG[k.TASK_BUILD]
        cwd = os.getcwd()
        for repo_path in d:
            os.chdir(repo_path)
            for action,value in d[repo_path].iteritems():
                if action == k.BUILD_BRANCH:
                    # Get the current branch
                    branch = value
                    p = execute_shell_cmd('git symbolic-ref --short -q HEAD')
                    current_branch = p.stdout.read()
                    retval = p.wait()
                    if current_branch != branch:
                        retval = execute_shell_cmd('git checkout ' + branch).wait()
                        if retval != 0:
                            return
                elif action == k.BUILD_CMD:
                    repo_name = basename(repo_path)
                    log_name = 'build-' + repo_name
                    with open(get_log_file_path(log_name), 'w') as log_file:
                        retval = execute_shell_cmd(value, stdout=log_file).wait()
                    if retval != 0:
                        return
            os.chdir(cwd)
        checkpoint(CHECKPOINT_BUILD, 'done')
            
    return { 'actions' : [build_git_repo],
             'targets' : [CHECKPOINT_BUILD]
         }
        
def task_copy_data():
    """
    Copy data from other places on the filesystem to the
    system directory.
    """
    def copy_remote_data():
        if not k.TASK_COPY_DATA in CONFIG:
            # Nothing to copy. Skip.
            checkpoint(CHECKPOINT_COPY_DATA, 'done')
            return
        if not os.path.exists(COPY_DATA_DIR):
            os.makedirs(COPY_DATA_DIR)
        d = CONFIG[k.TASK_COPY_DATA]
        if isinstance(d, list):
            for file_path in d:
                dest_path = os.path.join(COPY_DATA_DIR, basename(file_path))
                if not os.path.exists(dest_path):
                    shutil.copy2(file_path, COPY_DATA_DIR)
        else:
            dest_path = os.path.join(COPY_DATA_DIR, basename(d))
            if not os.path.exists(dest_path):
                shutil.copy2(d, COPY_DATA_DIR)
        checkpoint(CHECKPOINT_COPY_DATA, 'done')
    
    return { 'actions' : [copy_remote_data],
             'targets' : [CHECKPOINT_COPY_DATA]
         }

def task_compile_lm():
    """
    Calls KenLM to compile a language model.
    """
    def make_lm():
        sys.stderr.write('Looking for LM file ' + LM_FILE + '\n')
        if os.path.exists(LM_FILE):
            # Don't run KenLM if the LM already exists on disk
            # Otherwise, doit will always run this task at least
            # once.
            return
        mono_data = [CONFIG[k.CORPUS][k.CORPUS_TGT]]
        if k.CORPUS_MONO in CONFIG[k.CORPUS]:
            mono_files = CONFIG[k.CORPUS][k.CORPUS_MONO]
            if isinstance(mono_files, list):
                mono_data.extend(mono_files)
            else:
                mono_data.append(mono_files)
        mono_data = [qualify_path(x) for x in mono_data]
        bin_type = CONFIG[k.TASK_LM][k.LM_TYPE]
        options = ' '.join(CONFIG[k.TASK_LM][k.LM_OPTIONS])

        # Make the shell script for execution
        lmplz = os.path.join(KENLM_BIN, 'lmplz')
        build_bin = os.path.join(KENLM_BIN, 'build_binary')
        tmp_dir = os.path.join(SYSTEM_DIR, 'lm_tmp')
        if not os.path.exists(tmp_dir):
            os.makedirs(tmp_dir)

        # Compile ARPA file
        lmplz_cmd = "%s %s -T %s --arpa %s.arpa" % (lmplz, options, tmp_dir, LM_FILE)
        # Binarize ARPA file
        bin_cmd = "%s %s %s.arpa %s" % (build_bin, bin_type, LM_FILE, LM_FILE)
        with open(get_log_file_path('lm'), 'w') as log_file:
            if len(mono_data) == 1:
                with open(mono_data[0]) as infile:
                    retval = execute_shell_cmd(lmplz_cmd,
                                               stdin=infile,
                                               stdout=log_file).wait()
            else:
                # One-line cat command that handles both compressed
                # and uncompressed files in a platform-independent
                # way. Magic.
                cat_cmd = 'python -c "import fileinput; import sys; map(lambda x : sys.stdout.write(x), fileinput.input(openhook=fileinput.hook_compressed))" ' + ' '.join(mono_data)
                p_cat = execute_shell_cmd(cat_cmd)
                retval = execute_shell_cmd(lmplz_cmd,
                                               stdin=p_cat.stdout,
                                               stdout=log_file).wait()
            shutil.rmtree(tmp_dir)
            # Binarize
            if retval != 0 or not os.path.exists(LM_FILE + '.arpa'):
                return
            retval = execute_shell_cmd(bin_cmd,
                                       stdout=log_file).wait()
            if retval != 0:
                return
    
    return { 'actions' : [make_lm],
             'file_dep' : [CHECKPOINT_COPY_DATA],
             'targets' : [LM_FILE]
         }

def task_extract_tm():
    """
    Extract a translation model.
    TODO(spenceg) Assumes the new unfiltered TM builder,
    whatever that is.
    """
    def make_tm():
        sys.stderr.write('Looking for TM file ' + TM_FILE + '\n')
        if os.path.exists(TM_FILE):
            # Don't build the TM if it already exists on disk
            # Otherwise doit will run this task at least once
            return
        d = CONFIG[k.CORPUS]
        source = qualify_path(d[k.CORPUS_SRC])
        target = qualify_path(d[k.CORPUS_TGT])
        if isinstance(d[k.CORPUS_ALIGN], list):
            align = ' '.join([qualify_path(x) for x in d[k.CORPUS_ALIGN]]) 
        else:
            align = qualify_path(d[k.CORPUS_ALIGN])      
        tm_options = ' '.join(CONFIG[k.TASK_TM][k.TM_OPTIONS]) if k.TM_OPTIONS in CONFIG[k.TASK_TM] else ''
        java_cmd = get_java_cmd('edu.stanford.nlp.mt.train.DynamicTMBuilder')
        cmd = "%s %s -o %s %s %s %s" % (java_cmd, tm_options, TM_FILE, source, target, align)
        with open(get_log_file_path('tm'), 'w') as log_file:
            retval = execute_shell_cmd(cmd, stdout=log_file).wait()
        if retval != 0:
            os.remove(TM_FILE)
            return
            
    return { 'actions' : [make_tm],
             'file_dep' : [CHECKPOINT_COPY_DATA],
             'targets' : [TM_FILE]
         }

def generate_ini(filename, weights_file=None):
    """
    Generate a decoder ini file.
    """
    d = CONFIG[k.TASK_DECODER_CONFIG]
    lm_loader = d.get(k.DECODER_LM_LOADER, None)
    
    # Convert to phrasal ini file parameter format
    to_param = lambda x : '[%s]' % (x)
    seen_tm = False
    seen_lm = False
    seen_wts = False
    with open(filename, 'w') as outfile:
        ini = lambda x : outfile.write(str(x) + os.linesep)
        # Iterate over ini options
        
        for key in d[k.DECODER_OPTIONS]:
            if key == 'lmodel-file':
                seen_lm = True
            elif key == 'ttable-file':
                seen_tm = True
                
            ini(to_param(key))
            if isinstance(d[k.DECODER_OPTIONS][key], list):
                for value in d[k.DECODER_OPTIONS][key]:
                    ini(value)
            elif key == 'weights-file':
                seen_wts = True
                ini(weights_file if weights_file else d[k.DECODER_OPTIONS][key])
            else:
                ini(d[k.DECODER_OPTIONS][key])
            ini('')
    
        if weights_file and not seen_wts:
            ini(to_param('weights-file'))            
            ini(weights_file)
            ini('')
        if not seen_tm:
            ini(to_param('ttable-file'))
            ini(TM_FILE)
            ini('')
        if not seen_lm:
            ini(to_param('lmodel-file'))
            if lm_loader:
                ini('%s:%s' % (lm_loader, LM_FILE))
            else:
                ini(LM_FILE)
            ini('')

def task_tune():
    """
    Run tuning. Only supports online tuning right now.
    """
    def tune():
        # Check to see if decoder config contains a weights file
        # Or if the tuning task has been specified
        if not k.TASK_TUNE in CONFIG and 'weights-file' in CONFIG[k.TASK_DECODER_CONFIG][k.DECODER_OPTIONS]:
            # No need to run tuning
            wts_file = CONFIG[k.TASK_DECODER_CONFIG][k.DECODER_OPTIONS]['weights-file']
            wts_file = qualify_path(wts_file)
            shutil.copy2(wts_file, TUNE_WTS)
            generate_ini(DECODER_INI, TUNE_WTS)
            return

        # Run the tuner.
        generate_ini(DECODER_TUNE_INI)
        d = CONFIG[k.TASK_TUNE]
        source = d[k.TUNE_SRC]
        ref = d[k.TUNE_REFS]
        options = d[k.TUNE_OPTIONS]
        if isinstance(options, list):
            options = ' '.join(options)
        if isinstance(ref, list):
            options += ' -r ' + ','.join(ref)
            # Single ref as the argument to the tuning command
            ref = ref[0]
        options += ' -n ' + EXPERIMENT_NAME
        wts = d[k.TUNE_WTS]
        
        if not os.path.exists(qualify_path(wts)):
            execute_shell_cmd('touch %s' % wts)
        
        java_cmd = get_java_cmd('edu.stanford.nlp.mt.tune.OnlineTuner')
        cmd = '%s %s %s %s %s %s' % (java_cmd, options, source, ref, DECODER_TUNE_INI, wts)
        with open(get_log_file_path('tune'), 'w') as log_file:
            retval = execute_shell_cmd(cmd, stdout=log_file).wait()
        if retval != 0:
            return

        # Generate the decoder ini file
        generate_ini(DECODER_INI, TUNE_WTS)

    return { 'actions' : [tune],
             'file_dep' : [TM_FILE, LM_FILE],
             'targets' : [DECODER_INI, TUNE_WTS]
         }

def task_evaluate():
    """
    Decode and evaluate test set(s).
    """
    def evaluate(tgt_file, refs, metric):
        """
        Evaluate according to a specified metric.
        """
        java_cmd = get_java_cmd('edu.stanford.nlp.mt.tools.Evaluate')
        cmd = '%s %s %s' % (java_cmd, metric, refs)
        log_name = 'evaluate-' + tgt_file
        out_name = tgt_file + '.eval'
        with open(get_log_file_path(log_name), 'w') as log_file, open(make_abs(tgt_file)) as infile, open(make_abs(out_name), 'w') as outfile:
            retval = execute_shell_cmd(cmd, stdin=infile,
                                       stdout=outfile,
                                       stderr=log_file).wait()
        return retval == 0
    
    def decode():
        """
        Decode under the tuned model.
        """
        if not k.TASK_EVAL in CONFIG:
            checkpoint(CHECKPOINT_EVAL, 'done')
            return
        
        d = CONFIG[k.TASK_EVAL]
        metric = d[k.EVAL_METRIC]
        del d[k.EVAL_METRIC]
        for src in d:
            refs = d[src]
            if isinstance(refs, list):
                refs = ' '.join([qualify_path(x) for x in refs])
            src = qualify_path(src)
            src_name = basename(src)
            out_name = src_name + '.' +EXPERIMENT_NAME + '.trans'
            log_name = 'decode-' + src_name
            java_cmd = get_java_cmd('edu.stanford.nlp.mt.Phrasal')
            cmd = '%s %s -log-prefix %s.%s' % (java_cmd, DECODER_INI,
                                               src_name, EXPERIMENT_NAME)
            with open(get_log_file_path(log_name), 'w') as log_file, open(make_abs(out_name), 'w') as outfile, open(src) as infile:
                retval = execute_shell_cmd(cmd, stdin=infile,
                                           stdout=outfile,
                                           stderr=log_file).wait()
            if retval != 0 or not evaluate(out_name, refs, metric):
                return
        checkpoint(CHECKPOINT_EVAL, 'done')

    return { 'actions' : [decode],
             'file_dep' : [TM_FILE, LM_FILE, DECODER_INI],
             'targets' : [CHECKPOINT_EVAL]
         }
            
def task_learning_curve():
    """
    Generate a learning curve. Requires execution of the tuning task.
    """
    def generate_curve():
        if not k.TASK_LEARN_CURVE in CONFIG:
            checkpoint(CHECKPOINT_LEARN_CURVE, 'done')
            return

        # Load the tuning weights
        all_wts = [x for x in os.listdir(SYSTEM_DIR) if re.search('%s\.online.\d+.binwts' % (EXPERIMENT_NAME), x)]
        if len(all_wts) == 0:
            checkpoint(CHECKPOINT_LEARN_CURVE, 'done')
            return
        all_wts.sort(key=lambda x : int(re.search('online.(\d+).binwts', x).group(1)))

        # Iterate over the files for which we will generate curves
        d = CONFIG[k.TASK_LEARN_CURVE]
        metric = d[k.CURVE_METRIC]
        del d[k.CURVE_METRIC]
        for src in d:
            refs = d[src]
            if isinstance(refs, list):
                # CSV input format
                refs = ','.join([qualify_path(x) for x in refs])
            src = qualify_path(src)
            src_name = basename(src)
            out_name = src_name + '.learn-curve'
            log_name = 'learn-curve-' + src_name
            java_cmd = get_java_cmd('edu.stanford.nlp.mt.tools.OnlineLearningCurve')
            cmd = '%s %s %s %s %s %s' % (java_cmd, DECODER_INI,
                                         src, refs, metric,
                                         ' '.join(all_wts))
            with open(get_log_file_path(log_name), 'w') as log_file, open(make_abs(out_name), 'w') as outfile:
                retval = execute_shell_cmd(cmd, stdout=outfile,
                                           stderr=log_file).wait()
            if retval != 0:
                return
        checkpoint(CHECKPOINT_LEARN_CURVE, 'done')

    return { 'actions' : [generate_curve],
             'file_dep' : [TM_FILE, LM_FILE, DECODER_INI],
             'targets' : [CHECKPOINT_LEARN_CURVE]
    }

