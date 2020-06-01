#!/bin/sh -l

BRANCH_NAME=${GITHUB_REF#refs/heads/}

if [ -n "$4" ] && [ "$4" != "0000000000000000000000000000000000000000" ]
then
    git fetch --depth 1  origin $4
    FILES=`git diff FETCH_HEAD HEAD --diff-filter=AM --name-only|grep '\.clj$'|sed 's/^.*$/"&"/g'|tr "\n" " "`
elif [ "$BRANCH_NAME" = "master" ]
then
    git fetch --unshallow
    FILES=`git diff 4b825dc642cb6eb9a060e54bf8d69288fbee4904..HEAD --name-only|grep '\.clj$'|sed 's/^.*$/"&"/g'|tr "\n" " "`
else
    git fetch --unshallow
    BASE_NEXT_HASH=$(git log $BRANCH_NAME  --not `git for-each-ref --format='%(refname)' refs |grep -v /$BRANCH_NAME$ ` --pretty=format:"%H"|tail -n 1)
    FILES=`git diff $BASE_NEXT_HASH^ HEAD --diff-filter=AM --name-only|grep '\.clj$'|sed 's/^.*$/"&"/g'|tr "\n" " "`

    echo "BASE_HASH=" $BASE_NEXT_HASH
fi

echo "FILES=" $FILES

cd /lint-action-clj

clojure -m lint-action "{:linters $1 :cwd \"${GITHUB_WORKSPACE}\" :mode :github-action :relative-dir $2  :file-target :git :runner $3 :git-sha \"${GITHUB_SHA}\" :use-files true :files [$FILES] :eastwood-linters $5}"
