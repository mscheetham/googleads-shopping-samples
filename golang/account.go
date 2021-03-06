package main

// This file contains two demos of using the Accounts service:
// * Getting information about the current MC account as well as
//   adding/removing users and Adwords links, which can
//   be done by both multi-client and non-multi-client accounts.
// * Adding, listing, and removing subaccounts, which is only
//   valid for an MCA account.

import (
	"fmt"
	"log"
	"math/rand"

	"github.com/golang/protobuf/proto"
	"golang.org/x/net/context"
	"google.golang.org/api/content/v2"
)

// primaryAccountDemo gets the account information for the currently
// configured Merchant Center account, and then adds/removes a new
// user and/or new AdWords account link, if those fields in the
// configuration are not the default value.
func primaryAccountDemo(ctx context.Context, service *content.APIService, config *merchantInfo) {
	changed := false
	accounts := content.NewAccountsService(service)

	fmt.Println("Getting account information.")
	account, err := accounts.Get(config.MerchantID, config.MerchantID).Do()
	if err != nil {
		dumpAPIErrorAndStop(err, "Getting account information failed")
	}
	printAccount(account)

	if config.AccountSampleUser != "" {
		fmt.Printf("Adding user %s.\n", config.AccountSampleUser)
		account.Users = append(account.Users, &content.AccountUser{
			Admin:        proto.Bool(false),
			EmailAddress: config.AccountSampleUser,
		})
		changed = true
	}

	if config.AccountSampleAdwordsID != 0 {
		fmt.Printf("Linking Adwords ID %s.\n", config.AccountSampleAdwordsID)
		account.AdwordsLinks = append(account.AdwordsLinks, &content.AccountAdwordsLink{
			AdwordsId: config.AccountSampleAdwordsID,
			Status:    "active",
		})
		changed = true
	}

	if !changed {
		fmt.Println("No account changes available in sample configuration.")
		return
	}

	fmt.Println("Patching account information.")
	account, err = accounts.Patch(config.MerchantID, config.MerchantID, account).Do()
	if err != nil {
		dumpAPIErrorAndStop(err, "Patching account information failed")
	}
	printAccount(account)

	fmt.Println("Rolling back changes.")
	// Here, we do this on a fresh Account instance, to show the flexibility of patching.
	accountPatch := content.Account{}

	if config.AccountSampleUser != "" {
		users := [](*content.AccountUser){}
		fmt.Printf("Removing user %s.\n", config.AccountSampleUser)
		for _, user := range account.Users {
			if user.EmailAddress != config.AccountSampleUser {
				users = append(users, user)
			}
		}
		accountPatch.Users = users
	}

	if config.AccountSampleAdwordsID != 0 {
		links := []*content.AccountAdwordsLink{}
		fmt.Printf("Removing link to Adwords ID %d.\n", config.AccountSampleAdwordsID)
		for _, link := range account.AdwordsLinks {
			if link.AdwordsId != config.AccountSampleAdwordsID {
				links = append(links, link)
			}
		}
		accountPatch.AdwordsLinks = links
	}

	fmt.Println("Reverting account information.")
	account, err = accounts.Patch(config.MerchantID, config.MerchantID, &accountPatch).Do()
	if err != nil {
		dumpAPIErrorAndStop(err, "Reverting account information failed")
	}
	printAccount(account)
}

// multiClientAccountDemo retrieves the list of subaccounts from the
// configured multi-client account and adds and subsequently removes
// a new subaccount, relisting the subaccounts after each action to
// show the changes.  This demo cannot be run on a non-multi-client account.
func multiClientAccountDemo(ctx context.Context, service *content.APIService, config *merchantInfo) {
	if !config.IsMCA {
		fmt.Println("This demo requires a multi-client account.")
		return
	}
	accounts := content.NewAccountsService(service)

	fmt.Printf("Printing subaccounts of %d:\n", config.MerchantID)
	listCall := accounts.List(config.MerchantID)
	// Enable this to change the number of results listed by
	// per page:
	if false {
		listCall.MaxResults(100)
	}
	err := listCall.Pages(ctx, printAccountsPage)
	if err != nil {
		dumpAPIErrorAndStop(err, "Listing subaccounts failed")
	}
	fmt.Println("")

	accountName := fmt.Sprintf("sampleAccount#%d", rand.Int())
	account := &content.Account{
		Name: accountName,
	}
	fmt.Printf("Adding subaccount with name %s.\n", accountName)
	account, err = accounts.Insert(config.MerchantID, account).Do()
	if err != nil {
		dumpAPIErrorAndStop(err, "Adding subaccount failed")
	}
	fmt.Printf("Subaccount added with ID %d.\n", account.Id)

	fmt.Printf("Printing subaccounts of %d:\n", config.MerchantID)
	if err := accounts.List(config.MerchantID).Pages(ctx, printAccountsPage); err != nil {
		dumpAPIErrorAndStop(err, "Listing subaccounts failed")
	}
	fmt.Println("")

	fmt.Printf("Removing subaccount with ID %d.\n", account.Id)
	if err := accounts.Delete(config.MerchantID, account.Id).Do(); err != nil {
		dumpAPIErrorAndStop(err, "Removing subaccount failed")
	}
	fmt.Println("Subaccount removed.")

	fmt.Printf("Printing subaccounts of %d:\n", config.MerchantID)
	if err := accounts.List(config.MerchantID).Pages(ctx, printAccountsPage); err != nil {
		dumpAPIErrorAndStop(err, "Listing subaccounts failed")
	}
	fmt.Println("")
}

func printAccountsPage(res *content.AccountsListResponse) error {
	for _, account := range res.Resources {
		printAccount(account)
	}
	return nil
}

func printAccount(account *content.Account) {
	fmt.Printf("Information for account %d:\n", account.Id)
	fmt.Printf("- Display name: %s\n", account.Name)
	if len(account.Users) == 0 {
		fmt.Println("- No registered users.")
	} else {
		fmt.Println("- Registered users:")
		for _, user := range account.Users {
			fmt.Print("  - ")
			if user.Admin != nil && *user.Admin {
				fmt.Print("(ADMIN) ")
			}
			fmt.Println(user.EmailAddress)
		}
	}
	if len(account.AdwordsLinks) == 0 {
		fmt.Println("- No linked Adwords accounts.")
	} else {
		fmt.Println("- Linked Adwords accounts:")
		for _, link := range account.AdwordsLinks {
			fmt.Printf("  - %d: %s\n", link.AdwordsId, link.Status)
		}
	}
}

func checkMCAStatus(ctx context.Context, service *content.APIService, config *merchantInfo) bool {
	accounts := content.NewAccountsService(service)
	fmt.Println("Getting authenticated account information.")
	authinfo, err := accounts.Authinfo().Do()
	if err != nil {
		dumpAPIErrorAndStop(err, "Getting authenticated account information failed")
	}
	for _, i := range authinfo.AccountIdentifiers {
		switch config.MerchantID {
		case i.MerchantId:
			return false
		case i.AggregatorId:
			return true
		}
	}
	// If we didn't find it in the AccountIdentifiers, then the configured MC must either be a
	// subaccount of an MCA we are authenticated as, or the samples have been misconfigured
	// with an MC that we don't have access to.
	if _, err := accounts.Get(config.MerchantID, config.MerchantID).Do(); err != nil {
		log.Fatalf("Currently authenticated user does not have access to Merchant Center %d", config.MerchantID)
	}
	// A subaccount of an MCA cannot be an MCA itself.
	return false
}
