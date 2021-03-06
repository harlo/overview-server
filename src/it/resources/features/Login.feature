Feature: Login
  In order to let users manage their own stuff
  As a user
  I should be able to log in

  Scenario: Logging in
    Given I am not logged in
      And there is a user "user@example.org" with password "OlReshtob7"
    When I browse to the welcome page
     And I log in with email "user@example.org" and password "OlReshtob7"
    Then I should be logged in as "user@example.org"

  Scenario: Wrong password
    Given I am not logged in
      And there is a user "user@example.org" with password "OlReshtob7"
     When I browse to the welcome page
      And I log in with email "user@example.org" and password "OlReshtob71"
    Then I should not be logged in
     And I should see an error in the login form

  Scenario: Logging out
    Given I am logged in as "user@example.org"
    When I log out
    Then I should not be logged in
