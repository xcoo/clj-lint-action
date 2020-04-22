#!/bin/sh -l

cd /lint-action-clj

FILES=`echo $5|sed "s/'/\\"/g"`
clojure -m lint-action "{:linters $1 :cwd \"${GITHUB_WORKSPACE}\" :mode :github-action :relative-dir $2  :file-target :git :runner $3 :git-sha \"${GITHUB_SHA}\" :use-files $4 :files [$FILES]}"
