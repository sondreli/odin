

#client_id="a416206e-9b01-4bd2-92fc-9929572c6dcb"
#client_secret="4bd4f536-efba-407c-8ab2-71158809c07c"
client_id="06b5fe9c-5d99-4950-ae94-11b112154a44"
client_secret="173cdbcd-2349-4179-897e-d4e79d6f2bf7"

# Authenticate and authorize
function authenticate {
    state=$1
    echo 'open in browser, authenticate and copy code from the return uri'
    echo "https://api-auth.sparebank1.no/oauth/authorize?client_id=$client_id&state=$state&redirect_uri=https://localhost&finInst=fid-ostlandet&response_type=code" 
}


#code=a8e0938a44924b148c8d72daa736c3af
#state=6280696

function get_token {
    code=$1
    state=$2
    echo $1
    echo $2
    curl --location --request POST 'https://api-auth.sparebank1.no/oauth/token' \
        --header 'Content-Type: application/x-www-form-urlencoded' \
        --data-urlencode "client_id=$client_id" \
        --data-urlencode "client_secret=$client_secret" \
        --data-urlencode "code=$code" \
        --data-urlencode 'grant_type=authorization_code' \
        --data-urlencode "state=$state" \
        --data-urlencode 'redirect_uri=https://localhost'
}

#curl -X 'GET' 'https://api.sparebank1.no/personal/banking/transactions?accountKey=mekXQIKF91EoOWFdCvTf' \
 #--header "Authorization:Bearer Sx4REuGlHTMvnTa0MAX0IR0Afx3syML316A2rFMWqaLSp1LQDYoV4X" \
 #--header "Accept:application/vnd.sparebank1.v1+json;charset=utf-8"

#curl -X 'GET' 'https://api.sparebank1.no/personal/banking/transactions/9im0YIANYqeu5p_bTmya53oR5p3LLfP6Cq26J2N0yJ_MKIpUd0Lj0TkGq0EdyoIEyumyJr_Lost2hrV6Eiy2rDE4jau4Q6XmT2T82LH-mxUV5zMxXfSz9ctGyR_Mf9KaTw/details' \
 #--header "Authorization:Bearer Sx4REuGlHTMvnTa0MAX0IR0Afx3syML316A2rFMWqaLSp1LQDYoV4X" \
 #--header "Accept:application/vnd.sparebank1.v1+json;charset=utf-8"

case $1 in
    "auth")
        authenticate $2
        ;;
    "token")
        get_token $2 $3
        ;;
esac

