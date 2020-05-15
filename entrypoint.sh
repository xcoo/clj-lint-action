#!/bin/sh -l

git fetch --depth 1  origin $4
FILES=`git diff HEAD FETCH_HEAD --name-only|grep '\.clj$'|sed 's/^.*$/"&"/g'|tr "\n" " "`

cd /lint-action-clj

clojure -m lint-action "{:linters $1 :cwd \"${GITHUB_WORKSPACE}\" :mode :github-action :relative-dir $2  :file-target :git :runner $3 :git-sha \"${GITHUB_SHA}\" :use-files true :files [$FILES] :eastwood-linters $5}"
